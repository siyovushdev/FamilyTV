package ru.familytv.journey

import org.json.JSONArray
import org.json.JSONObject
import ru.familytv.adventure.LearningGoal

object JourneyParser {
    data class ValidationResult(
        val ok: Boolean,
        val journey: Journey?,
        val errors: List<String>,
        val warnings: List<String>,
    )

    fun parse(json: String): Journey {
        val result = parseAndValidate(json)
        require(result.ok && result.journey != null) {
            "Invalid journey: ${result.errors.joinToString("; ")}"
        }
        return result.journey
    }

    fun parseAndValidate(json: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return ValidationResult(false, null, listOf("JSON parse error: ${e.message}"), emptyList())
        }
        if (root.optInt("schemaVersion", 1) != 1) {
            errors += "Unsupported schemaVersion=${root.opt("schemaVersion")}"
        }
        val id = root.optString("id").trim()
        if (id.isBlank()) errors += "missing id"
        val title = root.optString("title").trim()
        if (title.isBlank()) errors += "missing title"
        val world = root.optString("world").trim()
        if (world.isBlank()) errors += "missing world"
        val startNodeId = root.optString("startNode").trim().ifBlank {
            root.optString("startNodeId").trim()
        }
        if (startNodeId.isBlank()) errors += "missing startNode"

        val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
        if (nodesArr.length() == 0) errors += "nodes empty"
        val nodes = mutableListOf<JourneyNode>()
        val seen = mutableSetOf<String>()
        for (i in 0 until nodesArr.length()) {
            val o = nodesArr.optJSONObject(i) ?: continue
            val node = parseNode(o, errors) ?: continue
            if (node.id in seen) errors += "duplicate node ${node.id}"
            seen += node.id
            nodes += node
        }
        if (startNodeId.isNotBlank() && startNodeId !in seen) {
            errors += "startNode '$startNodeId' missing"
        }

        // Ref integrity
        for (n in nodes) {
            n.next?.let { nxt ->
                if (nxt !in seen) errors += "node ${n.id} next → missing '$nxt'"
            }
            for (p in n.prerequisites) {
                if (p !in seen) errors += "node ${n.id} prerequisite missing '$p'"
            }
            for (p in n.prerequisitesAnyOf) {
                if (p !in seen) errors += "node ${n.id} prerequisitesAnyOf missing '$p'"
            }
            for (b in n.branches) {
                if (b.nextNodeId !in seen) {
                    errors += "node ${n.id} branch ${b.id} → missing '${b.nextNodeId}'"
                }
            }
            if (n.contentId.isNullOrBlank() &&
                n.type != JourneyNodeType.CONCLUSION &&
                n.type != JourneyNodeType.HUB
            ) {
                if (n.completionPolicy != CompletionPolicy.MANUAL) {
                    errors += "node ${n.id} missing contentId"
                }
            }
        }

        // Reachability from start
        if (startNodeId in seen && errors.none { it.startsWith("startNode") }) {
            val reach = reachable(startNodeId, nodes)
            for (n in nodes) {
                if (n.id !in reach && !n.optional) {
                    warnings += "unreachable node ${n.id}"
                }
            }
            // Ending: at least one conclusion or node with no next/branches
            val hasEnd = nodes.any {
                it.type == JourneyNodeType.CONCLUSION ||
                    (it.next == null && it.branches.isEmpty())
            }
            if (!hasEnd) errors += "missing ending (CONCLUSION or terminal node)"
        }

        // Soft cycle detection (warn unless intentional loop via optional)
        detectCycles(nodes)?.let { warnings += "cycle detected: $it" }

        if (errors.isNotEmpty()) {
            return ValidationResult(false, null, errors, warnings)
        }

        val journey = Journey(
            id = id,
            title = title,
            world = world,
            description = root.optString("description"),
            ageMin = root.optInt("ageMin").takeIf { root.has("ageMin") },
            ageMax = root.optInt("ageMax").takeIf { root.has("ageMax") },
            estimatedMinutes = root.optInt("estimatedMinutes").takeIf { root.has("estimatedMinutes") && it > 0 },
            learningGoals = parseGoals(root.optJSONArray("learningGoals")),
            startNodeId = startNodeId,
            nodes = nodes,
            mapStyle = runCatching {
                JourneyMapStyle.valueOf(root.optString("mapStyle", "PLANETS").uppercase())
            }.getOrDefault(JourneyMapStyle.PLANETS),
            completionLine = root.optString("completionLine")
                .ifBlank { "Мы закончили экспедицию." },
            version = root.optInt("version", 1),
        )
        return ValidationResult(true, journey, emptyList(), warnings)
    }

    private fun parseNode(o: JSONObject, errors: MutableList<String>): JourneyNode? {
        val id = o.optString("id").trim()
        if (id.isBlank()) {
            errors += "node missing id"
            return null
        }
        val type = runCatching {
            JourneyNodeType.valueOf(o.getString("type").uppercase())
        }.getOrElse {
            errors += "node $id unknown type"
            return null
        }
        val policy = runCatching {
            CompletionPolicy.valueOf(
                o.optString("completionPolicy", "CONTENT_COMPLETED").uppercase(),
            )
        }.getOrDefault(CompletionPolicy.CONTENT_COMPLETED)

        val branches = mutableListOf<JourneyBranch>()
        o.optJSONArray("branches")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                branches += JourneyBranch(
                    id = b.getString("id"),
                    label = b.getString("label"),
                    spokenLabel = b.optString("spokenLabel").ifBlank { null },
                    nextNodeId = b.getString("next"),
                )
            }
        }
        val ageVariants = mutableListOf<AgeVariant>()
        o.optJSONArray("ageVariants")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                ageVariants += AgeVariant(
                    ageMin = v.getInt("ageMin"),
                    ageMax = v.getInt("ageMax"),
                    contentId = v.optString("contentId").ifBlank { null },
                    labelHint = v.optString("labelHint").ifBlank { null },
                )
            }
        }
        val nextRaw = o.optString("next")
        val next = when {
            !o.has("next") || o.isNull("next") -> null
            nextRaw.equals("null", ignoreCase = true) || nextRaw.isBlank() -> null
            else -> nextRaw
        }
        return JourneyNode(
            id = id,
            title = o.optString("title").ifBlank { id },
            mapLabel = o.optString("mapLabel").ifBlank { o.optString("title").ifBlank { id } },
            spokenLabel = o.optString("spokenLabel").ifBlank { null },
            contentId = o.optString("contentId").ifBlank { null },
            type = type,
            prerequisites = stringList(o.optJSONArray("prerequisites")),
            prerequisitesAnyOf = stringList(o.optJSONArray("prerequisitesAnyOf")),
            next = next,
            branches = branches,
            optional = o.optBoolean("optional", false),
            journalUnlocks = stringList(o.optJSONArray("journalUnlocks")),
            completionPolicy = policy,
            learningGoals = parseGoals(o.optJSONArray("learningGoals")),
            ageVariants = ageVariants,
            mapProgress = o.optDouble("mapProgress").takeIf { o.has("mapProgress") }?.toFloat(),
        )
    }

    private fun reachable(start: String, nodes: List<JourneyNode>): Set<String> {
        val byId = nodes.associateBy { it.id }
        val out = mutableSetOf<String>()
        val q = ArrayDeque<String>()
        q.add(start)
        while (q.isNotEmpty()) {
            val id = q.removeFirst()
            if (!out.add(id)) continue
            val n = byId[id] ?: continue
            n.next?.let { q.add(it) }
            n.branches.forEach { q.add(it.nextNodeId) }
            // Also nodes that list this as prerequisite become reachable once we know graph edges from next/branches;
            // prerequisite-only edges: scan
            for (other in nodes) {
                if (id in other.prerequisites || id in other.prerequisitesAnyOf) {
                    q.add(other.id)
                }
            }
        }
        return out
    }

    private fun detectCycles(nodes: List<JourneyNode>): String? {
        val byId = nodes.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun dfs(id: String, path: List<String>): String? {
            if (id in done) return null
            if (id in visiting) return (path + id).joinToString("→")
            visiting += id
            val n = byId[id] ?: return null
            val nexts = buildList {
                n.next?.let { add(it) }
                n.branches.forEach { add(it.nextNodeId) }
            }
            for (x in nexts) {
                dfs(x, path + id)?.let { return it }
            }
            visiting -= id
            done += id
            return null
        }
        for (n in nodes) {
            dfs(n.id, emptyList())?.let { return it }
        }
        return null
    }

    private fun parseGoals(arr: JSONArray?): List<LearningGoal> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i)
                runCatching { LearningGoal.valueOf(raw) }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) add(s)
            }
        }
    }
}
