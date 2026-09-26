package ru.familytv.content

import android.content.Context
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.content.distribution.ContentSyncEngine
import ru.familytv.content.distribution.ContentSyncPrefs
import ru.familytv.content.distribution.DevTrustStoreFactory
import ru.familytv.interactive.ExperienceProgressStore
import ru.familytv.interaction.FamilyActionDispatcher
import ru.familytv.interaction.NarrationCoordinator
import ru.familytv.narration.FamilyNarrationProvider
import ru.familytv.narration.NarrationAssetResolver
import ru.familytv.narration.NarrationProvider
import ru.familytv.narration.NoOpNarration
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.FamilySettingsStore
import ru.familytv.parent.ParentPinStore
import ru.familytv.parent.ScreenTimeTracker
import ru.familytv.progress.WatchProgressStore
import ru.familytv.session.SessionProgressStore
import java.io.File

/** Process-scoped platform services. Created lazily; no AI models. */
object PlatformServices {
    @Volatile private var repository: LocalContentRepository? = null
    @Volatile private var watchProgress: WatchProgressStore? = null
    @Volatile private var packageStore: LocalContentPackageStore? = null
    @Volatile private var experienceProgress: ExperienceProgressStore? = null
    @Volatile private var sessionProgress: SessionProgressStore? = null
    @Volatile private var contentSync: ContentSyncEngine? = null
    @Volatile private var syncPrefs: ContentSyncPrefs? = null
    @Volatile private var parentPin: ParentPinStore? = null
    @Volatile private var familySettings: FamilySettingsStore? = null
    @Volatile private var activityHistory: ActivityHistoryStore? = null
    @Volatile private var screenTime: ScreenTimeTracker? = null
    @Volatile private var narration: NarrationProvider? = null
    @Volatile private var narrationCoordinator: NarrationCoordinator? = null
    @Volatile private var narrationResolver: NarrationAssetResolver? = null
    @Volatile private var adventureProgress: ru.familytv.adventure.AdventureProgressStore? = null
    @Volatile private var adventureEvents: ru.familytv.adventure.AdventureEventStore? = null
    @Volatile private var trialEvents: ru.familytv.trial.TrialEventStore? = null
    @Volatile private var actionDispatcher: FamilyActionDispatcher? = null
    @Volatile private var journeyProgress: ru.familytv.journey.JourneyProgressStore? = null
    @Volatile private var discoveryJournal: ru.familytv.journey.DiscoveryJournalStore? = null
    @Volatile private var journeyCatalog: ru.familytv.journey.JourneyCatalog? = null
    @Volatile private var journeyCoordinator: ru.familytv.journey.JourneyCoordinator? = null

    /** Optional content scope for package narration resolution (set by Activities). */
    @Volatile var narrationContentItem: ContentItem? = null

    /** Optional journey scope when launching content from a map node. */
    @Volatile var activeJourneyId: String? = null

    fun actionDispatcher(): FamilyActionDispatcher {
        actionDispatcher?.let { return it }
        synchronized(this) {
            actionDispatcher?.let { return it }
            return FamilyActionDispatcher().also { actionDispatcher = it }
        }
    }

    fun narrationResolver(context: Context): NarrationAssetResolver {
        narrationResolver?.let { return it }
        synchronized(this) {
            narrationResolver?.let { return it }
            val app = context.applicationContext
            return NarrationAssetResolver(app, contentRepository(app)).also { narrationResolver = it }
        }
    }

    fun packageStore(context: Context): LocalContentPackageStore {
        packageStore?.let { return it }
        synchronized(this) {
            packageStore?.let { return it }
            val app = context.applicationContext
            return LocalContentPackageStore(app) {
                contentRepository(app).bundledContentIds()
            }.also { packageStore = it }
        }
    }

    fun contentRepository(context: Context): LocalContentRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val app = context.applicationContext
            val packages = packageStore(app)
            return LocalContentRepository(app, packages).also { repository = it }
        }
    }

    fun watchProgress(context: Context): WatchProgressStore {
        watchProgress?.let { return it }
        synchronized(this) {
            watchProgress?.let { return it }
            return WatchProgressStore(context.applicationContext).also { watchProgress = it }
        }
    }

    fun experienceProgress(context: Context): ExperienceProgressStore {
        experienceProgress?.let { return it }
        synchronized(this) {
            experienceProgress?.let { return it }
            return ExperienceProgressStore(context.applicationContext).also { experienceProgress = it }
        }
    }

    fun sessionProgress(context: Context): SessionProgressStore {
        sessionProgress?.let { return it }
        synchronized(this) {
            sessionProgress?.let { return it }
            return SessionProgressStore(context.applicationContext).also { sessionProgress = it }
        }
    }

    fun contentSync(context: Context): ContentSyncEngine {
        contentSync?.let { return it }
        synchronized(this) {
            contentSync?.let { return it }
            val app = context.applicationContext
            return ContentSyncEngine(
                context = app,
                packageStore = packageStore(app),
                trustStore = DevTrustStoreFactory.fromAssets(app),
            ).also { contentSync = it }
        }
    }

    fun syncPrefs(context: Context): ContentSyncPrefs {
        syncPrefs?.let { return it }
        synchronized(this) {
            syncPrefs?.let { return it }
            return ContentSyncPrefs(context.applicationContext).also { syncPrefs = it }
        }
    }

    fun parentPin(context: Context): ParentPinStore {
        parentPin?.let { return it }
        synchronized(this) {
            parentPin?.let { return it }
            return ParentPinStore(context.applicationContext).also { parentPin = it }
        }
    }

    fun familySettings(context: Context): FamilySettingsStore {
        familySettings?.let { return it }
        synchronized(this) {
            familySettings?.let { return it }
            return FamilySettingsStore(context.applicationContext).also { familySettings = it }
        }
    }

    fun activityHistory(context: Context): ActivityHistoryStore {
        activityHistory?.let { return it }
        synchronized(this) {
            activityHistory?.let { return it }
            return ActivityHistoryStore(context.applicationContext).also { activityHistory = it }
        }
    }

    fun screenTime(context: Context): ScreenTimeTracker {
        screenTime?.let { return it }
        synchronized(this) {
            screenTime?.let { return it }
            val app = context.applicationContext
            return ScreenTimeTracker(app, familySettings(app), activityHistory(app)).also { screenTime = it }
        }
    }

    fun narration(context: Context): NarrationProvider {
        narration?.let { return it }
        synchronized(this) {
            narration?.let { return it }
            val app = context.applicationContext
            val settings = familySettings(app)
            return FamilyNarrationProvider(app) {
                settings.accessibility().narrationEnabled
            }.also { narration = it }
        }
    }

    fun narrationCoordinator(context: Context): NarrationCoordinator {
        narrationCoordinator?.let { return it }
        synchronized(this) {
            narrationCoordinator?.let { return it }
            val app = context.applicationContext
            val settings = familySettings(app)
            val provider = narration(app)
            val resolver = narrationResolver(app)
            return NarrationCoordinator(
                provider = provider,
                readingMode = { settings.readingSupportMode() },
                resolveAudio = { ref -> resolver.resolve(narrationContentItem, ref) },
            ).also { narrationCoordinator = it }
        }
    }

    fun adventureProgress(context: Context): ru.familytv.adventure.AdventureProgressStore {
        adventureProgress?.let { return it }
        synchronized(this) {
            adventureProgress?.let { return it }
            return ru.familytv.adventure.AdventureProgressStore(context.applicationContext)
                .also { adventureProgress = it }
        }
    }

    fun adventureEvents(context: Context): ru.familytv.adventure.AdventureEventStore {
        adventureEvents?.let { return it }
        synchronized(this) {
            adventureEvents?.let { return it }
            return ru.familytv.adventure.AdventureEventStore(context.applicationContext)
                .also { adventureEvents = it }
        }
    }

    fun trialEvents(context: Context): ru.familytv.trial.TrialEventStore {
        trialEvents?.let { return it }
        synchronized(this) {
            trialEvents?.let { return it }
            return ru.familytv.trial.TrialEventStore(context.applicationContext)
                .also { trialEvents = it }
        }
    }

    fun journeyProgress(context: Context): ru.familytv.journey.JourneyProgressStore {
        journeyProgress?.let { return it }
        synchronized(this) {
            journeyProgress?.let { return it }
            return ru.familytv.journey.JourneyProgressStore(context.applicationContext)
                .also { journeyProgress = it }
        }
    }

    fun discoveryJournal(context: Context): ru.familytv.journey.DiscoveryJournalStore {
        discoveryJournal?.let { return it }
        synchronized(this) {
            discoveryJournal?.let { return it }
            return ru.familytv.journey.DiscoveryJournalStore(context.applicationContext)
                .also { discoveryJournal = it }
        }
    }

    fun journeyCatalog(context: Context): ru.familytv.journey.JourneyCatalog {
        journeyCatalog?.let { return it }
        synchronized(this) {
            journeyCatalog?.let { return it }
            return ru.familytv.journey.JourneyCatalog(context.applicationContext)
                .also { journeyCatalog = it }
        }
    }

    fun journeyCoordinator(context: Context): ru.familytv.journey.JourneyCoordinator {
        journeyCoordinator?.let { return it }
        synchronized(this) {
            journeyCoordinator?.let { return it }
            val app = context.applicationContext
            return ru.familytv.journey.JourneyCoordinator(
                catalog = journeyCatalog(app),
                progressStore = journeyProgress(app),
                journal = discoveryJournal(app),
                trial = trialEvents(app),
            ).also { journeyCoordinator = it }
        }
    }

    fun resetForTests() {
        synchronized(this) {
            repository = null
            watchProgress = null
            packageStore = null
            experienceProgress = null
            sessionProgress = null
            contentSync = null
            syncPrefs = null
            parentPin = null
            familySettings = null
            activityHistory = null
            screenTime = null
            adventureProgress = null
            adventureEvents = null
            trialEvents = null
            journeyProgress = null
            discoveryJournal = null
            journeyCatalog = null
            journeyCoordinator = null
            activeJourneyId = null
            narration?.release()
            narration = null
            narrationCoordinator = null
            narrationResolver = null
            narrationContentItem = null
            actionDispatcher = null
        }
    }
}
