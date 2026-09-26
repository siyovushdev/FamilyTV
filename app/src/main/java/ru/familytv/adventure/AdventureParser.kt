package ru.familytv.adventure

import org.json.JSONArray
import org.json.JSONObject

object AdventureParser {
    fun parse(json: String): Adventure {
        val root = JSONObject(json)
        val scenesArr = root.getJSONArray("scenes")
        val scenes = buildList {
            for (i in 0 until scenesArr.length()) {
                add(parseScene(scenesArr.getJSONObject(i)))
            }
        }
        require(scenes.isNotEmpty()) { "Adventure needs scenes" }
        val start = root.optString("startSceneId").ifBlank { scenes.first().id }
        val vars = mutableMapOf<String, Int>()
        root.optJSONObject("variables")?.let { obj ->
            obj.keys().forEach { k -> vars[k] = obj.optInt(k, 0) }
        }
        val objectives = root.optJSONArray("objectives")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        AdventureObjective(
                            id = o.getString("id"),
                            title = o.optString("title"),
                            description = o.optString("description"),
                        ),
                    )
                }
            }
        } ?: emptyList()
        val guideObj = root.optJSONObject("guide")
        val guide = GuideCharacterDef(
            id = guideObj?.optString("id")?.ifBlank { "lumi" } ?: "lumi",
            name = guideObj?.optString("name")?.ifBlank { "Луми" } ?: "Луми",
            defaultEmotion = guideObj?.optString("defaultEmotion")
                ?.let { runCatching { GuideEmotion.valueOf(it) }.getOrNull() }
                ?: GuideEmotion.CURIOUS,
        )
        return Adventure(
            id = root.getString("id"),
            title = root.getString("title"),
            world = root.optString("world").ifBlank { "explore" },
            description = root.optString("description"),
            learningGoals = parseGoals(root.optJSONArray("learningGoals")),
            estimatedMinutes = root.optInt("estimatedMinutes").takeIf { it > 0 },
            difficultyMin = root.optJSONObject("difficulty")?.optInt("min", 1) ?: 1,
            difficultyMax = root.optJSONObject("difficulty")?.optInt("max", 3) ?: 3,
            startSceneId = start,
            variables = vars,
            objectives = objectives,
            guide = guide,
            scenes = scenes,
            schemaVersion = root.optInt("schemaVersion", 1),
        )
    }

    fun validate(adventure: Adventure): List<String> {
        val errors = mutableListOf<String>()
        val ids = adventure.scenes.map { it.id }.toSet()
        if (ids.size != adventure.scenes.size) errors += "duplicate scene ids"
        if (adventure.startSceneId !in ids) errors += "startSceneId missing"
        if (adventure.learningGoals.isEmpty()) errors += "adventure learningGoals empty"
        adventure.scenes.forEach { scene ->
            scene.next?.let { if (it !in ids) errors += "scene ${scene.id} next→$it missing" }
            if (scene is ChallengeScene) {
                val c = scene.challenge
                c.successNext?.let {
                    if (it !in ids) errors += "challenge ${c.id} successNext→$it missing"
                }
                if (c.learningGoals.isEmpty()) {
                    errors += "challenge ${c.id} missing learningGoals"
                }
                if (c.scaffolding.hint1.isNullOrBlank() &&
                    c.scaffolding.hint2.isNullOrBlank() &&
                    c.scaffolding.explanation.isNullOrBlank()
                ) {
                    errors += "challenge ${c.id} missing scaffolding hints"
                }
            }
            if (scene is DialogueScene) {
                scene.onMappedNext?.let { if (it !in ids) errors += "dialogue ${scene.id} onMappedNext missing" }
                if (scene.accepted.isEmpty()) errors += "dialogue ${scene.id} has no accepted mappings"
            }
            if (scene is OffscreenScene) {
                scene.next?.let { if (it !in ids) errors += "offscreen ${scene.id} next→$it missing" }
            }
        }
        // Detect simple unreachable scenes from start (BFS)
        val reachable = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(adventure.startSceneId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!reachable.add(id)) continue
            val scene = adventure.scenes.find { it.id == id } ?: continue
            scene.next?.let { queue.add(it) }
            if (scene is ChallengeScene) {
                scene.challenge.successNext?.let { queue.add(it) }
            }
            if (scene is DialogueScene) {
                scene.onMappedNext?.let { queue.add(it) }
                scene.next?.let { queue.add(it) }
            }
        }
        val unreachable = ids - reachable
        if (unreachable.isNotEmpty()) {
            errors += "unreachable scenes: ${unreachable.joinToString()}"
        }
        return errors
    }

    private fun parseScene(o: JSONObject): AdventureScene {
        val id = o.getString("id")
        val kind = SceneKind.valueOf(o.getString("kind"))
        val next = o.optString("next").takeIf { it.isNotBlank() && it != "null" }
        val audio = o.optString("narrationAudio").ifBlank { null }
        val group = o.optString("groupMode").ifBlank { null }
            ?.let { runCatching { GroupMode.valueOf(it) }.getOrNull() }
            ?: GroupMode.SOLO_OR_GROUP
        val bg = o.optString("background").ifBlank { null }
        return when (kind) {
            SceneKind.STORY -> StoryScene(
                id = id,
                text = o.getString("text"),
                continueLabel = o.optString("continueLabel").ifBlank { "Дальше" },
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
                guideLine = o.optString("guideLine").ifBlank { null },
                guideEmotion = o.optString("guideEmotion").ifBlank { null }
                    ?.let { runCatching { GuideEmotion.valueOf(it) }.getOrNull() }
                    ?: GuideEmotion.CURIOUS,
                spokenPrompt = o.optString("spokenPrompt").ifBlank { null },
            )
            SceneKind.CHALLENGE -> ChallengeScene(
                id = id,
                challenge = parseChallenge(o.getJSONObject("challenge")),
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
            )
            SceneKind.DIALOGUE -> DialogueScene(
                id = id,
                prompt = o.getString("prompt"),
                accepted = parseAccepted(o.optJSONArray("accepted")),
                unknownReply = o.optString("unknownReply").ifBlank { "Расскажи ещё раз своими словами." },
                onMappedNext = o.optString("onMappedNext").ifBlank { null },
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
            )
            SceneKind.OFFSCREEN -> OffscreenScene(
                id = id,
                prompt = o.getString("prompt"),
                continueLabel = o.optString("continueLabel").ifBlank { "Готово" },
                voiceConfirmPhrases = stringList(o.optJSONArray("voiceConfirmPhrases"))
                    .ifEmpty { listOf("готово", "сделали", "нашли", "да") },
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
                guideLine = o.optString("guideLine").ifBlank { null },
            )
            SceneKind.MOVE -> MoveScene(
                id = id,
                prompt = o.getString("prompt"),
                durationSeconds = o.optInt("durationSeconds", 20),
                continueLabel = o.optString("continueLabel").ifBlank { "Готово" },
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
            )
            SceneKind.REFLECTION -> ReflectionScene(
                id = id,
                text = o.getString("text"),
                continueLabel = o.optString("continueLabel").ifBlank { "К приключениям" },
                next = next,
                narrationAudio = audio,
                groupMode = group,
                background = bg,
            )
        }
    }

    private fun parseChallenge(o: JSONObject): ChallengeDef {
        val mechanic = MechanicType.valueOf(o.getString("mechanic"))
        val scaffolding = o.optJSONObject("scaffolding")?.let { sc ->
            ScaffoldingDef(
                hint1 = sc.optString("hint1").ifBlank { null },
                hint2 = sc.optString("hint2").ifBlank { null },
                visualHint = sc.optBoolean("visualHint", false),
                explanation = sc.optString("explanation").ifBlank { null },
                simplifiedParams = sc.optJSONObject("simplifiedParams")
                    ?.let { parseParams(mechanic, it) },
            )
        } ?: ScaffoldingDef()
        val successSets = mutableMapOf<String, Int>()
        o.optJSONObject("successSets")?.let { obj ->
            obj.keys().forEach { k -> successSets[k] = obj.optInt(k, 1) }
        }
        return ChallengeDef(
            id = o.getString("id"),
            mechanic = mechanic,
            prompt = o.getString("prompt"),
            learningGoals = parseGoals(o.optJSONArray("learningGoals")),
            scaffolding = scaffolding,
            params = parseParams(mechanic, o.getJSONObject("params")),
            voiceAccept = stringList(o.optJSONArray("voiceAccept")),
            successSets = successSets,
            successNext = o.optString("successNext").ifBlank { null },
            promptAudio = o.optString("promptAudio").ifBlank { null },
            spokenPrompt = o.optString("spokenPrompt").ifBlank { null },
        )
    }

    private fun parseParams(mechanic: MechanicType, o: JSONObject): MechanicParams = when (mechanic) {
        MechanicType.VISUAL_SEARCH -> {
            val objects = o.getJSONArray("objects").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(
                            SearchObject(
                                id = x.getString("id"),
                                label = x.getString("label"),
                                voiceAliases = stringList(x.optJSONArray("voiceAliases")),
                                category = x.optString("category").ifBlank { null },
                            ),
                        )
                    }
                }
            }
            VisualSearchParams(
                objects = objects,
                requiredIds = stringList(o.getJSONArray("requiredIds")).toSet(),
                minSelect = o.optInt("minSelect").takeIf { it > 0 },
            )
        }
        MechanicType.SORT_AND_CLASSIFY -> {
            val categories = o.getJSONArray("categories").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(SortCategory(x.getString("id"), x.getString("label")))
                    }
                }
            }
            val items = o.getJSONArray("items").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(
                            SortItem(
                                id = x.getString("id"),
                                label = x.getString("label"),
                                correctCategoryId = x.getString("correctCategoryId"),
                                voiceAliases = stringList(x.optJSONArray("voiceAliases")),
                            ),
                        )
                    }
                }
            }
            SortClassifyParams(categories, items)
        }
        MechanicType.PATTERN_SEQUENCE -> PatternSequenceParams(
            display = stringList(o.getJSONArray("display")),
            blankIndex = o.getInt("blankIndex"),
            options = stringList(o.getJSONArray("options")),
            correctOption = o.getString("correctOption"),
            voiceAccept = stringList(o.optJSONArray("voiceAccept")),
        )
        MechanicType.PATH_MAZE -> {
            val blocked = o.optJSONArray("blocked")?.let { arr ->
                buildSet {
                    for (i in 0 until arr.length()) add(GridPos.parse(arr.getString(i)))
                }
            } ?: emptySet()
            val required = o.optJSONArray("requiredVisit")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) add(GridPos.parse(arr.getString(i)))
                }
            } ?: emptyList()
            val labels = mutableMapOf<GridPos, String>()
            o.optJSONObject("cellLabels")?.let { obj ->
                obj.keys().forEach { k -> labels[GridPos.parse(k)] = obj.getString(k) }
            }
            PathMazeParams(
                width = o.getInt("width"),
                height = o.getInt("height"),
                start = GridPos.parse(o.getString("start")),
                goal = GridPos.parse(o.getString("goal")),
                blocked = blocked,
                requiredVisit = required,
                cellLabels = labels,
                allowedLabelRule = o.optString("allowedLabelRule").ifBlank { null }
                    ?.let { runCatching { PathLabelRule.valueOf(it) }.getOrNull() }
                    ?: PathLabelRule.ANY,
            )
        }
        MechanicType.PREDICT_AND_TEST -> {
            val options = o.getJSONArray("options").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(
                            PredictOption(
                                id = x.getString("id"),
                                label = x.getString("label"),
                                voiceAliases = stringList(x.optJSONArray("voiceAliases")),
                            ),
                        )
                    }
                }
            }
            val outcomes = mutableMapOf<String, String>()
            o.getJSONObject("outcomeByOptionId").let { obj ->
                obj.keys().forEach { k -> outcomes[k] = obj.getString(k) }
            }
            PredictAndTestParams(
                options = options,
                outcomeByOptionId = outcomes,
                testPhrases = stringList(o.optJSONArray("testPhrases")).ifEmpty {
                    listOf("проверить", "проверим", "тест")
                },
                correctPredictionId = o.optString("correctPredictionId").ifBlank { null },
                knowledgeTopic = o.optString("knowledgeTopic").ifBlank { null },
            )
        }
        MechanicType.BUILD_AND_TEST -> {
            val slots = o.getJSONArray("slots").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(BuildSlot(x.getString("id"), x.getString("label")))
                    }
                }
            }
            val parts = o.getJSONArray("parts").let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val x = arr.getJSONObject(i)
                        add(
                            BuildPart(
                                id = x.getString("id"),
                                label = x.getString("label"),
                                voiceAliases = stringList(x.optJSONArray("voiceAliases")),
                                slotId = x.getString("slotId"),
                            ),
                        )
                    }
                }
            }
            val success = mutableMapOf<String, String>()
            o.getJSONObject("successPlacements").let { obj ->
                obj.keys().forEach { k -> success[k] = obj.getString(k) }
            }
            BuildAndTestParams(
                slots = slots,
                parts = parts,
                successPlacements = success,
                successObservation = o.getString("successObservation"),
                failObservation = o.getString("failObservation"),
                testPhrases = stringList(o.optJSONArray("testPhrases")).ifEmpty {
                    listOf("проверить", "проверим", "тест")
                },
            )
        }
    }

    private fun parseAccepted(arr: JSONArray?): List<DialogueAccept> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    DialogueAccept(
                        id = o.getString("id"),
                        phrases = stringList(o.getJSONArray("phrases")),
                        reply = o.getString("reply"),
                        learningNote = o.optString("learningNote").ifBlank { null },
                    ),
                )
            }
        }
    }

    private fun parseGoals(arr: JSONArray?): List<LearningGoal> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.getString(i)
                runCatching { LearningGoal.valueOf(raw) }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }
}
