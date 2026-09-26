package ru.familytv.journey

/**
 * Pure journey orchestration — available nodes, resume, branch choice, completion.
 * Does not launch Activities.
 */
object JourneyEngine {
    fun initialProgress(journey: Journey, clock: () -> Long = { System.currentTimeMillis() }): JourneyProgress =
        JourneyProgress(
            journeyId = journey.id,
            openNodeIds = setOf(journey.startNodeId),
            completedNodeIds = emptySet(),
            lastVisitedNodeId = null,
            started = false,
            completed = false,
            journeyVersion = journey.version,
            updatedAt = clock(),
        )

    /**
     * If stored progress version mismatches or nodes disappeared, rebuild safely.
     */
    fun sanitize(
        journey: Journey,
        stored: JourneyProgress?,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JourneyProgress {
        if (stored == null) return initialProgress(journey, clock)
        val valid = journey.nodeIds()
        if (stored.journeyVersion != journey.version) {
            // Keep completed nodes that still exist; reopen start if empty
            val completed = stored.completedNodeIds.intersect(valid)
            val open = recomputeOpen(journey, completed).ifEmpty { setOf(journey.startNodeId) }
            return stored.copy(
                openNodeIds = open,
                completedNodeIds = completed,
                lastVisitedNodeId = stored.lastVisitedNodeId?.takeIf { it in valid },
                journeyVersion = journey.version,
                completed = isJourneyComplete(journey, completed),
                updatedAt = clock(),
            )
        }
        val completed = stored.completedNodeIds.intersect(valid)
        val open = stored.openNodeIds.intersect(valid).ifEmpty {
            recomputeOpen(journey, completed).ifEmpty { setOf(journey.startNodeId) }
        }
        return stored.copy(
            openNodeIds = open,
            completedNodeIds = completed,
            lastVisitedNodeId = stored.lastVisitedNodeId?.takeIf { it in valid },
            completed = isJourneyComplete(journey, completed),
        )
    }

    fun views(
        journey: Journey,
        progress: JourneyProgress,
        contentExists: (String) -> Boolean,
        ageMin: Int? = null,
        ageMax: Int? = null,
    ): List<JourneyNodeView> {
        val completed = progress.completedNodeIds
        return journey.nodes.map { node ->
            val resolved = resolveContentId(node, ageMin, ageMax)
            val availability = when {
                node.id in completed -> NodeAvailability.COMPLETED
                !prerequisitesMet(node, completed) -> NodeAvailability.FUTURE
                resolved != null && !contentExists(resolved) -> NodeAvailability.MISSING_CONTENT
                node.id in progress.openNodeIds || prerequisitesMet(node, completed) ->
                    NodeAvailability.AVAILABLE
                else -> NodeAvailability.FUTURE
            }
            JourneyNodeView(node, availability, resolved)
        }
    }

    fun resolveContentId(node: JourneyNode, ageMin: Int?, ageMax: Int?): String? {
        if (ageMin != null || ageMax != null) {
            val aMin = ageMin ?: 0
            val aMax = ageMax ?: 99
            val variant = node.ageVariants.firstOrNull { v ->
                v.ageMin <= aMax && v.ageMax >= aMin && !v.contentId.isNullOrBlank()
            }
            if (variant?.contentId != null) return variant.contentId
        }
        return node.contentId
    }

    fun prerequisitesMet(node: JourneyNode, completed: Set<String>): Boolean {
        if (node.prerequisitesAnyOf.isNotEmpty()) {
            if (node.prerequisitesAnyOf.none { it in completed }) return false
        }
        return node.prerequisites.all { it in completed }
    }

    fun start(journey: Journey, progress: JourneyProgress, clock: () -> Long = { System.currentTimeMillis() }): JourneyProgress =
        progress.copy(
            started = true,
            openNodeIds = progress.openNodeIds.ifEmpty { setOf(journey.startNodeId) },
            updatedAt = clock(),
        )

    fun visit(
        journey: Journey,
        progress: JourneyProgress,
        nodeId: String,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JourneyProgress {
        if (journey.node(nodeId) == null) return progress
        return progress.copy(
            started = true,
            lastVisitedNodeId = nodeId,
            openNodeIds = progress.openNodeIds + nodeId,
            updatedAt = clock(),
        )
    }

    fun completeNode(
        journey: Journey,
        progress: JourneyProgress,
        nodeId: String,
        branchId: String? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JourneyProgress {
        val node = journey.node(nodeId) ?: return progress
        val completed = progress.completedNodeIds + nodeId
        val branches = progress.chosenBranches.toMutableMap()
        if (branchId != null) {
            branches[nodeId] = branchId
        }
        val newlyOpen = mutableSetOf<String>()
        // Linear next
        node.next?.let { newlyOpen += it }
        // Branch targets: open all as parallel choices (no forced corridor)
        if (node.branches.isNotEmpty()) {
            node.branches.forEach { newlyOpen += it.nextNodeId }
            if (branchId != null) {
                // already recorded in chosenBranches
            }
        }
        // Nodes whose prerequisites are now met
        for (n in journey.nodes) {
            if (n.id !in completed && prerequisitesMet(n, completed)) {
                newlyOpen += n.id
            }
        }
        val open = (progress.openNodeIds - nodeId) + newlyOpen - completed
        val done = isJourneyComplete(journey, completed)
        return progress.copy(
            started = true,
            completedNodeIds = completed,
            openNodeIds = open,
            lastVisitedNodeId = nodeId,
            chosenBranches = branches,
            completed = done,
            updatedAt = clock(),
        )
    }

    fun chooseBranch(
        journey: Journey,
        progress: JourneyProgress,
        fromNodeId: String,
        branchId: String,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JourneyProgress {
        val node = journey.node(fromNodeId) ?: return progress
        val branch = node.branches.find { it.id == branchId } ?: return progress
        // Completing the branching node if not already, then open target
        val base = if (fromNodeId in progress.completedNodeIds) {
            progress
        } else {
            completeNode(journey, progress, fromNodeId, branchId, clock)
        }
        return base.copy(
            openNodeIds = base.openNodeIds + branch.nextNodeId,
            chosenBranches = base.chosenBranches + (fromNodeId to branchId),
            lastVisitedNodeId = branch.nextNodeId,
            updatedAt = clock(),
        )
    }

    fun resumeNodeId(journey: Journey, progress: JourneyProgress): String? {
        progress.lastVisitedNodeId?.let { last ->
            val n = journey.node(last) ?: return@let
            if (last !in progress.completedNodeIds && prerequisitesMet(n, progress.completedNodeIds)) {
                return last
            }
        }
        // Prefer available open required nodes
        val openRequired = progress.openNodeIds
            .mapNotNull { journey.node(it) }
            .filter { !it.optional && it.id !in progress.completedNodeIds }
            .sortedBy { it.mapProgress ?: 0f }
        openRequired.firstOrNull()?.let { return it.id }
        return progress.openNodeIds.firstOrNull { it !in progress.completedNodeIds }
            ?: journey.startNodeId.takeIf { it !in progress.completedNodeIds }
    }

    fun availableNextLabels(journey: Journey, progress: JourneyProgress): List<String> =
        views(journey, progress, { true })
            .filter { it.availability == NodeAvailability.AVAILABLE && it.node.id !in progress.completedNodeIds }
            .map { it.node.mapLabel }

    fun unexploredLabels(journey: Journey, progress: JourneyProgress): List<String> =
        journey.nodes
            .filter { it.id !in progress.completedNodeIds && !it.optional }
            .map { it.mapLabel }

    private fun recomputeOpen(journey: Journey, completed: Set<String>): Set<String> {
        if (completed.isEmpty()) return setOf(journey.startNodeId)
        val open = mutableSetOf<String>()
        for (n in journey.nodes) {
            if (n.id in completed) {
                n.next?.takeIf { it !in completed }?.let { open += it }
                if (n.branches.isNotEmpty()) {
                    n.branches.forEach { b ->
                        if (b.nextNodeId !in completed) open += b.nextNodeId
                    }
                }
            } else if (prerequisitesMet(n, completed)) {
                open += n.id
            }
        }
        return open
    }

    private fun isJourneyComplete(journey: Journey, completed: Set<String>): Boolean {
        val endings = journey.nodes.filter { it.type == JourneyNodeType.CONCLUSION }
        if (endings.isNotEmpty()) {
            return endings.any { it.id in completed }
        }
        val required = journey.nodes.filter { !it.optional }
        return required.isNotEmpty() && required.all { it.id in completed }
    }
}
