package ru.familytv.games.world

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.familytv.content.CatalogManifestParser
import ru.familytv.content.ContentCatalog
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentOverrideStore
import ru.familytv.content.ContentRepository
import ru.familytv.content.ContentSourceKind
import ru.familytv.content.ContentType
import ru.familytv.content.LocalContentRepository
import ru.familytv.content.ManifestValidationResult
import ru.familytv.content.ResolvedMedia
import ru.familytv.content.memoryPrefs
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.games.CountCombineCatalog
import ru.familytv.games.CountCombineHub
import ru.familytv.games.CountCombineLoad
import ru.familytv.games.CountCombineParser
import ru.familytv.games.GamesDestination
import ru.familytv.games.Verdict

class CountCombineTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun datasetAAndBStayDistinctThroughWrongHelpAndSuccess() {
        val apples = CountCombineFixtures.apples()
        val baskets = CountCombineFixtures.baskets()
        assertEquals(listOf(2 to 3, 1 to 2, 2 to 2), apples.pairs)
        assertEquals(listOf(1 to 1, 3 to 1, 1 to 3), baskets.pairs)
        assertNotEquals(apples.pairs, baskets.pairs)
        assertNotEquals(apples.lines, baskets.lines)

        val opened = CountCombineHub.openFromHub(listOf(baskets, apples), "more")
        assertEquals("game-math-baskets", opened?.contentId)
        assertEquals("game-math", CountCombineHub.openFromHub(listOf(baskets, apples), "card:0")?.contentId)
        assertNull(CountCombineHub.openFromHub(listOf(baskets), "card:0"))

        val state = GamesState().enterMath(requireNotNull(opened))
        assertEquals(1, state.apples.left)
        assertEquals(1, state.apples.right)
        assertEquals(baskets.lines.separate, state.prompt())
        state.beginCombine(); state.finishCombine()
        assertEquals(baskets.lines.ask, state.prompt())
        assertEquals(Verdict.WRONG, state.answerMath(1))
        assertEquals(1 to 1, state.apples.left to state.apples.right)
        assertEquals(baskets.lines.wrong, state.prompt())
        assertTrue(state.help())
        assertEquals(baskets.lines.help, state.prompt())
        assertEquals("Считаем этот набор. Один, два.", state.countingNarration())
        assertNotEquals(apples.lines.help, state.prompt())
        state.finishCounting()
        assertEquals(Verdict.CORRECT, state.answerMath(2))
        assertEquals(baskets.lines.success, state.prompt())
        assertEquals("2", state.equationResult)
        state.nextMath()
        assertEquals(3 to 1, state.apples.left to state.apples.right)
    }

    @Test fun swappedFileForSecondIdIsRejectedInsteadOfPlayingApples() {
        val apples = CountCombineFixtures.text("count-combine-apples.json")
        val baskets = CountCombineFixtures.text("count-combine-baskets.json")
        val items = listOf(
            game("game-math", "count-combine-apples.json", 20),
            game("game-math-baskets", "count-combine-baskets.json", 21),
        )
        val repo = MapRepo(items, mapOf(
            "count-combine-apples.json" to apples,
            "count-combine-baskets.json" to baskets,
        ))
        val a = CountCombineCatalog.load(repo, "game-math") as CountCombineLoad.Ready
        val b = CountCombineCatalog.load(repo, "game-math-baskets") as CountCombineLoad.Ready
        assertEquals(applesPairs(), a.definition.pairs)
        assertEquals(listOf(1 to 1, 3 to 1, 1 to 3), b.definition.pairs)

        val swapped = MapRepo(items, mapOf("count-combine-baskets.json" to apples))
        val rejected = CountCombineCatalog.load(swapped, "game-math-baskets")
        assertTrue(rejected is CountCombineLoad.Rejected)
        assertTrue((rejected as CountCombineLoad.Rejected).reason.contains("game-math-baskets"))
    }

    @Test fun unknownAndMalformedDefinitionsDoNotBindTheApplesSet() {
        val apples = CountCombineFixtures.text("count-combine-apples.json")
        assertTrue(CountCombineParser.parse(apples, "game-math-baskets") is CountCombineLoad.Rejected)
        assertTrue(CountCombineParser.parse("{", "game-math") is CountCombineLoad.Rejected)
        assertTrue(CountCombineParser.parse("""{"schemaVersion":1,"template":"count_combine","contentId":"game-math","title":"Математика","lines":{},"countingWords":["Один"],"rounds":[]}""", "game-math") is CountCombineLoad.Rejected)
        val repo = MapRepo(emptyList(), emptyMap())
        assertTrue(CountCombineCatalog.load(repo, "game-math-baskets") is CountCombineLoad.Rejected)
        val state = GamesState()
        assertNull(state.mathSet)
        assertThrows(IllegalStateException::class.java) { state.enter(GameWorld.MATH) }
        assertNull(state.mathContentId)
    }

    @Test fun bundledCatalogPointsEachIdAtItsOwnFile() {
        val manifest = JSONObject(File(CountCombineFixtures.main, "assets/catalog/manifest.json").readText())
        val items = manifest.getJSONArray("items")
        val byId = (0 until items.length()).associate { items.getJSONObject(it).getString("id") to items.getJSONObject(it) }
        val math = requireNotNull(byId["game-math"])
        val baskets = requireNotNull(byId["game-math-baskets"])
        assertEquals("game/math", math.getString("route"))
        assertEquals("game/math", baskets.getString("route"))
        assertEquals("count-combine-apples.json", math.getString("experienceRef"))
        assertEquals("count-combine-baskets.json", baskets.getString("experienceRef"))
        assertNotEquals(math.getString("experienceRef"), baskets.getString("experienceRef"))
        val lesson = File(CountCombineFixtures.main, "../../../lessons/count-combine-baskets/experiences/count-combine-baskets.json")
        assertEquals(
            File(CountCombineFixtures.main, "assets/catalog/experiences/count-combine-baskets.json").readText(),
            lesson.readText(),
        )
    }

    @Test fun runtimeNoLongerOwnsTheAppleRoundsOrPrompts() {
        val state = File(CountCombineFixtures.main, "java/ru/familytv/games/world/GamesState.kt").readText()
        assertFalse(state.contains("2 to 3"))
        assertFalse(state.contains("Соединим яблоки"))
        val activity = File(CountCombineFixtures.main, "java/ru/familytv/games/world/GamesActivity.kt").readText()
        assertTrue(activity.contains("CountCombineHub.openFromHub"))
        assertTrue(activity.contains("EXTRA_CONTENT_ID"))
        assertTrue(activity.contains("\"more:\$index\""))
        val navigator = File(CountCombineFixtures.main, "java/ru/familytv/home/ContentNavigator.kt").readText()
        assertTrue(navigator.contains("gameIntent(activity, item.route, item.id)"))
    }

    @Test fun secondExtraOpensBesideTheFirstWithoutTakingTheAppleCard() {
        val apples = CountCombineFixtures.apples()
        val baskets = CountCombineFixtures.baskets()
        val packaged = CountCombineParser.parse(
            File(repoRoot(), "lessons/count-combine-c/experiences/count-combine-c.json").readText(),
            "game-math-package-c",
        )
        assertTrue(packaged is CountCombineLoad.Ready)
        val c = (packaged as CountCombineLoad.Ready).definition
        assertEquals(listOf(2 to 2, 3 to 2, 1 to 1), c.pairs)
        assertNotEquals(apples.pairs, c.pairs)
        assertNotEquals(baskets.pairs, c.pairs)
        val unordered = listOf(c, baskets, apples)
        assertEquals("game-math", CountCombineHub.openFromHub(unordered, "card:0")?.contentId)
        assertNull(CountCombineHub.openFromHub(unordered, "card:1"))
        val playable = listOf(apples, baskets, c)
        assertEquals("game-math-baskets", CountCombineHub.openFromHub(playable, "more")?.contentId)
        assertEquals("game-math-package-c", CountCombineHub.openFromHub(playable, "more:1")?.contentId)
    }

    @Test fun installedPackageAppearsAfterReloadAndOpensTheSameMathActivity() {
        val manifest = File(CountCombineFixtures.main, "assets/catalog/manifest.json").readText()
        assertFalse(manifest.contains("game-math-package-c"))
        assertFalse(manifest.contains("count-combine-c"))
        val reserved = CatalogManifestParser.parse(manifest, ContentSourceKind.BUNDLED).catalog?.items?.map { it.id }?.toSet().orEmpty()
        assertFalse(reserved.contains("game-math-package-c"))
        val zip = File(repoRoot(), "dist/count-combine-c-v1.zip")
        assertTrue("Build lessons/count-combine-c before this test: ${zip.absolutePath}", zip.isFile)
        val store = LocalContentPackageStore(
            packagesRoot = tmp.newFolder("pkgs"),
            importRoot = tmp.newFolder("imp"),
            prefs = memoryPrefs(),
            reservedContentIds = { reserved },
        )
        val installed = store.importZip(zip)
        assertTrue(installed.errors.joinToString(), installed.ok)
        assertEquals("count-combine-c", installed.packageId)
        val assets = File(CountCombineFixtures.main, "assets")
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { path -> File(assets, path).inputStream() },
            catalogRoot = tmp.newFolder("catalog"),
            packageStore = store,
        )
        val catalog = repo.reload()
        assertEquals(ContentSourceKind.MERGED, catalog.source)
        assertFalse(repo.bundledContentIds().contains("game-math-package-c"))
        val item = requireNotNull(repo.getItem("game-math-package-c"))
        assertEquals("count-combine-c", item.packageId)
        assertEquals(1, item.packageVersion)
        assertEquals("game/math", item.route)
        assertEquals("ru.familytv.games.MathVoiceGameActivity", GamesDestination.fromRoute(item.route)?.activityName)
        val hub = File(CountCombineFixtures.main, "java/ru/familytv/games/GameHubActivity.kt").readText()
        assertTrue(hub.contains("class MathVoiceGameActivity : GamesActivity()"))
        val sets = CountCombineCatalog.playable(repo)
        assertEquals(listOf("game-math", "game-math-baskets", "game-math-package-c"), sets.map { it.contentId })
        assertEquals(listOf(2 to 3, 1 to 2, 2 to 2), sets[0].pairs)
        assertEquals(listOf(1 to 1, 3 to 1, 1 to 3), sets[1].pairs)
        assertEquals(listOf(2 to 2, 3 to 2, 1 to 1), sets[2].pairs)
        assertEquals("Пакетный набор собран.", sets[2].lines.success)
        val state = GamesState().enterMath(requireNotNull(CountCombineHub.openFromHub(sets, "more:1")))
        assertEquals("game-math-package-c", state.mathContentId)
        assertEquals(2 to 2, state.apples.left to state.apples.right)
        assertEquals("Соберём яблоки из пакета!", state.prompt())
        assertEquals("game-math", CountCombineHub.openFromHub(sets, "card:0")?.contentId)
        assertEquals("game-math-baskets", CountCombineHub.openFromHub(sets, "more")?.contentId)
    }

    @Test fun basketVisitIsNotRecordedAsTheAppleSet() {
        val baskets = CountCombineFixtures.baskets()
        val identity = visitIdentity(GameWorld.MATH, baskets)
        assertEquals("game-math-baskets", identity.contentId)
        assertNotEquals("game-math", identity.contentId)
        val visit = GameVisit()
        visit.begin(GameWorld.MATH, 1000, identity.contentId, identity.title, identity.topics)
        val end = requireNotNull(visit.finish(4000))
        assertEquals("game-math-baskets", end.contentId)
        assertEquals("Ещё яблоки", end.title)
        assertEquals(listOf("math", "counting"), end.topics)
    }

    private fun applesPairs() = listOf(2 to 3, 1 to 2, 2 to 2)

    private fun repoRoot(): File = File(CountCombineFixtures.main, "../../..").canonicalFile

    private fun game(id: String, ref: String, sort: Int) = ContentItem(
        id = id,
        title = id,
        type = ContentType.GAME,
        route = "game/math",
        experienceRef = ref,
        sortOrder = sort,
    )

    private class MapRepo(
        private val items: List<ContentItem>,
        private val files: Map<String, String>,
    ) : ContentRepository {
        private fun catalog() = ContentCatalog(1, 1, ContentSourceKind.BUNDLED, items)
        override fun getCatalog() = catalog()
        override fun getItem(id: String) = items.find { it.id == id }
        override fun getEnabledItems() = items.filter { it.enabled }
        override fun itemsForSection(sectionId: String) = items.filter { sectionId in it.homeSections }
        override fun setEnabled(id: String, enabled: Boolean) = Unit
        override fun reload() = catalog()
        override fun validateActiveManifest() = ManifestValidationResult(true, catalog())
        override fun importFromImportDirectory() = validateActiveManifest()
        override fun catalogVersion() = 1
        override fun activeSource() = ContentSourceKind.BUNDLED
        override fun resolveMedia(item: ContentItem): ResolvedMedia? = null
        override fun openExperienceJson(item: ContentItem) = item.experienceRef?.let { files[it] }
        override fun openSessionJson(item: ContentItem): String? = null
        override fun openAdventureJson(item: ContentItem): String? = null
    }
}
