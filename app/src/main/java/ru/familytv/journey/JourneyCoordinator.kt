package ru.familytv.journey

import android.content.Context
import android.util.Log
import ru.familytv.content.ContentItem
import ru.familytv.content.PlatformServices
import ru.familytv.creative.StoryStore
import ru.familytv.trial.TrialEventStore

/**
 * Bridges content completion → journey progress + journal unlocks.
 * Call sites: Adventure / Creative / Video / Home.
 */
class JourneyCoordinator(
    private val catalog: JourneyCatalog,
    private val progressStore: JourneyProgressStore,
    private val journal: DiscoveryJournalStore,
    private val trial: TrialEventStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        JourneyCatalog(context.applicationContext),
        PlatformServices.journeyProgress(context),
        PlatformServices.discoveryJournal(context),
        PlatformServices.trialEvents(context),
    )

    fun activeJourneyId(): String? =
        progressStore.listActive().maxByOrNull { it.updatedAt }?.journeyId

    fun ensureStarted(journeyId: String): JourneyProgress? {
        val journey = catalog.get(journeyId) ?: return null
        val sanitized = JourneyEngine.sanitize(journey, progressStore.get(journeyId), clock)
        val started = if (!sanitized.started) {
            JourneyEngine.start(journey, sanitized, clock).also {
                trial.record(
                    TrialEventStore.JOURNEY_STARTED,
                    journeyId,
                    mapOf("world" to journey.world),
                )
            }
        } else sanitized
        progressStore.save(started)
        return started
    }

    fun progressFor(journeyId: String): Pair<Journey, JourneyProgress>? {
        val journey = catalog.get(journeyId) ?: return null
        val p = JourneyEngine.sanitize(journey, progressStore.get(journeyId), clock)
        return journey to p
    }

    fun resumeTarget(journeyId: String? = null): ResumeTarget? {
        val id = journeyId ?: activeJourneyId() ?: catalog.all().firstOrNull()?.id ?: return null
        val (journey, progress) = progressFor(id) ?: return null
        if (!progress.started) {
            val node = journey.node(journey.startNodeId) ?: return null
            return ResumeTarget(journey, progress, node, node.contentId)
        }
        val nodeId = JourneyEngine.resumeNodeId(journey, progress) ?: return null
        val node = journey.node(nodeId) ?: return null
        return ResumeTarget(journey, progress, node, node.contentId)
    }

    fun onContentOpened(contentId: String, journeyId: String? = null) {
        val jid = journeyId ?: findJourneyForContent(contentId) ?: return
        val journey = catalog.get(jid) ?: return
        var progress = JourneyEngine.sanitize(journey, progressStore.get(jid), clock)
        if (!progress.started) {
            progress = JourneyEngine.start(journey, progress, clock)
            trial.record(TrialEventStore.JOURNEY_STARTED, jid)
        }
        val node = journey.nodes.find { it.contentId == contentId || matchesAgeVariant(it, contentId) }
            ?: return
        progress = JourneyEngine.visit(journey, progress, node.id, clock)
        trial.record(
            TrialEventStore.JOURNEY_NODE_OPENED,
            contentId,
            mapOf("journeyId" to jid, "nodeId" to node.id),
        )
        if (node.completionPolicy == CompletionPolicy.CONTENT_OPENED) {
            progress = completeAndUnlock(journey, progress, node)
        } else {
            progressStore.save(progress)
        }
    }

    fun onContentCompleted(contentId: String, journeyId: String? = null) {
        val jid = journeyId ?: findJourneyForContent(contentId) ?: activeJourneyId()
        if (jid == null) {
            // Still allow adventure journal without active journey
            unlockAdventureVisit(contentId)
            return
        }
        val journey = catalog.get(jid) ?: return
        var progress = JourneyEngine.sanitize(journey, progressStore.get(jid), clock)
        if (!progress.started) {
            progress = JourneyEngine.start(journey, progress, clock)
            trial.record(TrialEventStore.JOURNEY_STARTED, jid)
        }
        val node = journey.nodes.find { it.contentId == contentId || matchesAgeVariant(it, contentId) }
        if (node == null) {
            unlockAdventureVisit(contentId, jid)
            return
        }
        if (node.id in progress.completedNodeIds) {
            progressStore.save(progress)
            return
        }
        progress = completeAndUnlock(journey, progress, node)
        Log.i(TAG, "node_completed journey=$jid node=${node.id} content=$contentId")
    }

    fun onBranchChosen(journeyId: String, fromNodeId: String, branchId: String) {
        val journey = catalog.get(journeyId) ?: return
        var progress = JourneyEngine.sanitize(journey, progressStore.get(journeyId), clock)
        progress = JourneyEngine.chooseBranch(journey, progress, fromNodeId, branchId, clock)
        progressStore.save(progress)
        trial.record(
            TrialEventStore.JOURNEY_BRANCH_CHOSEN,
            journeyId,
            mapOf("from" to fromNodeId, "branch" to branchId),
        )
    }

    fun onStoryCreated(storyId: String, title: String, world: String?) {
        journal.unlock(
            authoredUnlockId = "story:$storyId",
            type = JournalEntryType.STORY_CREATED,
            title = title,
            body = "Мы создали историю «$title».",
            spokenBody = "Мы создали историю: $title.",
            world = world,
            journeyId = activeJourneyId(),
            storyId = storyId,
            clock = clock,
        )
        // Complete CREATE nodes that point at lumi-story
        onContentCompleted("lumi-story")
    }

    fun onExperimentObserved(
        experimentId: String,
        contentId: String?,
        variants: List<String>,
        outcome: String,
        spoken: String? = null,
    ) {
        val obs = ExperimentObservation(
            experimentId = experimentId,
            contentId = contentId,
            variants = variants,
            observedOutcome = outcome,
            spokenOutcome = spoken,
            journeyId = activeJourneyId(),
            createdAtMs = clock(),
        )
        journal.addObservation(obs)
        // Authored fact unlocks tied to experiment
        applyUnlockIds(
            listOf("exp-$experimentId", experimentId),
            journeyId = activeJourneyId(),
            contentId = contentId,
            discovery = true,
        )
    }

    fun onKnowledgeDiscovered(knowledgeId: String, world: String?, contentId: String?) {
        applyUnlockIds(
            listOf("fact-$knowledgeId", knowledgeId),
            journeyId = activeJourneyId(),
            contentId = contentId,
            knowledgeId = knowledgeId,
            world = world,
            discovery = true,
        )
    }

    fun markManualNode(journeyId: String, nodeId: String) {
        val journey = catalog.get(journeyId) ?: return
        val node = journey.node(nodeId) ?: return
        var progress = JourneyEngine.sanitize(journey, progressStore.get(journeyId), clock)
        progress = completeAndUnlock(journey, progress, node)
    }

    fun syncStoriesIntoJournal(storyStore: StoryStore) {
        for (s in storyStore.list()) {
            if (journal.hasUnlock("story:${s.id}")) continue
            journal.unlock(
                authoredUnlockId = "story:${s.id}",
                type = JournalEntryType.STORY_CREATED,
                title = s.title,
                body = "Мы создали историю «${s.title}».",
                spokenBody = "Мы создали историю: ${s.title}.",
                storyId = s.id,
                clock = { s.createdAtMs },
            )
        }
    }

    private fun completeAndUnlock(
        journey: Journey,
        progress: JourneyProgress,
        node: JourneyNode,
    ): JourneyProgress {
        val next = JourneyEngine.completeNode(journey, progress, node.id, clock = clock)
        progressStore.save(next)
        trial.record(
            TrialEventStore.JOURNEY_NODE_COMPLETED,
            node.contentId,
            mapOf("journeyId" to journey.id, "nodeId" to node.id),
        )
        applyUnlockIds(
            node.journalUnlocks,
            journeyId = journey.id,
            contentId = node.contentId,
            world = journey.world,
            discovery = true,
        )
        when (node.type) {
            JourneyNodeType.ADVENTURE, JourneyNodeType.DISCOVERY -> {
                journal.unlock(
                    authoredUnlockId = "adv-node:${journey.id}:${node.id}",
                    type = JournalEntryType.ADVENTURE_COMPLETED,
                    title = node.mapLabel,
                    body = "Мы исследовали: ${node.mapLabel}.",
                    spokenBody = "Мы исследовали ${node.mapLabel}.",
                    world = journey.world,
                    journeyId = journey.id,
                    contentId = node.contentId,
                    clock = clock,
                )
            }
            JourneyNodeType.CONCLUSION -> {
                journal.unlock(
                    authoredUnlockId = "journey-done:${journey.id}",
                    type = JournalEntryType.JOURNEY_SUMMARY,
                    title = journey.title,
                    body = journey.completionLine,
                    spokenBody = journey.completionLine,
                    world = journey.world,
                    journeyId = journey.id,
                    clock = clock,
                )
            }
            else -> Unit
        }
        if (next.completed && !progress.completed) {
            trial.record(TrialEventStore.JOURNEY_COMPLETED, journey.id)
        }
        return next
    }

    private fun applyUnlockIds(
        ids: List<String>,
        journeyId: String?,
        contentId: String? = null,
        knowledgeId: String? = null,
        world: String? = null,
        discovery: Boolean,
    ) {
        for (uid in ids) {
            val def = catalog.unlockDef(uid) ?: continue
            if (def.requireDiscovery && !discovery) continue
            journal.unlock(
                authoredUnlockId = def.id,
                type = def.type,
                title = def.title,
                body = def.body,
                spokenBody = def.spokenBody,
                world = def.world ?: world,
                journeyId = journeyId,
                contentId = contentId,
                knowledgeId = knowledgeId ?: def.knowledgeId,
                clock = clock,
            )
        }
    }

    private fun unlockAdventureVisit(contentId: String, journeyId: String? = null) {
        journal.unlock(
            authoredUnlockId = "visit:$contentId",
            type = JournalEntryType.WORLD_VISITED,
            title = contentId,
            body = "Мы побывали в занятии.",
            contentId = contentId,
            journeyId = journeyId,
            clock = clock,
        )
    }

    private fun findJourneyForContent(contentId: String): String? {
        for (j in catalog.all()) {
            val p = progressStore.get(j.id)
            if (p?.started == true && !p.completed) {
                if (j.nodes.any { it.contentId == contentId || matchesAgeVariant(it, contentId) }) {
                    return j.id
                }
            }
        }
        // Prefer journey that contains this content even if not started
        return catalog.all().firstOrNull { j ->
            j.nodes.any { it.contentId == contentId || matchesAgeVariant(it, contentId) }
        }?.id
    }

    private fun matchesAgeVariant(node: JourneyNode, contentId: String): Boolean =
        node.ageVariants.any { it.contentId == contentId }

    data class ResumeTarget(
        val journey: Journey,
        val progress: JourneyProgress,
        val node: JourneyNode,
        val contentId: String?,
    )

    companion object {
        private const val TAG = "FamilyJourney"
    }
}
