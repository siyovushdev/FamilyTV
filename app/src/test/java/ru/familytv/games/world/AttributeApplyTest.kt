package ru.familytv.games.world

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.familytv.content.CatalogManifestParser
import ru.familytv.content.ContentOverrideStore
import ru.familytv.content.ContentSourceKind
import ru.familytv.content.LocalContentRepository
import ru.familytv.content.memoryPrefs
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.games.AttributeApplyCatalog
import ru.familytv.games.AttributeApplyHub
import ru.familytv.games.AttributeApplyLoad
import ru.familytv.games.AttributeApplyParser
import ru.familytv.games.GameColor
import ru.familytv.games.GamesDestination
import ru.familytv.games.Verdict

class AttributeApplyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun datasetAKeepsTheBlueWingThroughWrongHelpCorrectAndLaunch() {
        val rocket = AttributeApplyFixtures.rocket()
        assertEquals(GameColor.BLUE, rocket.correct)
        assertEquals(listOf(GameColor.RED, GameColor.BLUE, GameColor.YELLOW), rocket.paints)
        assertEquals("Покрась крыло ракеты в синий цвет!", rocket.lines.prompt)
        val state = GamesState().enterColor(rocket)
        assertEquals(1, state.hintIndex)
        assertEquals(rocket.lines.prompt, state.prompt())
        assertEquals(Verdict.WRONG, state.paint(GameColor.RED))
        assertEquals(GameColor.RED, state.painted)
        assertEquals(rocket.lines.wrong, state.prompt())
        assertFalse(state.launch())
        assertTrue(state.help())
        assertEquals(GameColor.RED, state.painted)
        assertEquals(GameFeedback.HELP, state.feedback)
        assertEquals(rocket.lines.help, state.prompt())
        assertFalse(state.launch())
        assertEquals(Verdict.CORRECT, state.paint(GameColor.BLUE))
        assertEquals(GameColor.BLUE, state.painted)
        assertEquals(rocket.lines.success, state.prompt())
        assertFalse(state.launched)
        assertTrue(state.launch())
        assertEquals(rocket.lines.launched, state.prompt())
        assertFalse(state.launch())
        assertNull(state.paint(GameColor.RED))
        assertEquals(GameColor.BLUE, state.painted)
    }

    @Test fun datasetBDoesNotUseTheBlueTargetFromA() {
        val sun = AttributeApplyFixtures.sun()
        assertEquals(GameColor.YELLOW, sun.correct)
        assertNotEquals(AttributeApplyFixtures.rocket().correct, sun.correct)
        assertNotEquals(AttributeApplyFixtures.rocket().lines, sun.lines)
        val state = GamesState().enterColor(sun)
        assertEquals("game-color-sun", state.colorContentId)
        assertEquals(Verdict.WRONG, state.paint(GameColor.BLUE))
        assertEquals(GameColor.BLUE, state.painted)
        assertEquals(sun.lines.wrong, state.prompt())
        assertFalse(state.launch())
        assertTrue(state.help())
        assertEquals(GameColor.BLUE, state.painted)
        assertNotEquals(GameFeedback.CORRECT, state.feedback)
        assertEquals(Verdict.CORRECT, state.paint(GameColor.YELLOW))
        assertEquals(GameColor.YELLOW, state.painted)
        assertEquals(sun.lines.success, state.prompt())
        assertTrue(state.launch())
        assertEquals(sun.lines.launched, state.prompt())
        val identity = visitIdentity(GameWorld.COLOR, null, sun)
        assertEquals("game-color-sun", identity.contentId)
        assertNotEquals("game-color", identity.contentId)
    }

    @Test fun swappedFileForSecondIdIsRejectedInsteadOfPaintingBlue() {
        val rocket = AttributeApplyFixtures.text("attribute-apply-rocket.json")
        val rejected = AttributeApplyParser.parse(rocket, "game-color-sun")
        assertTrue(rejected is AttributeApplyLoad.Rejected)
        assertTrue((rejected as AttributeApplyLoad.Rejected).reason.contains("game-color-sun"))
        val state = GamesState()
        assertNull(state.colorSet)
        assertThrows(IllegalStateException::class.java) { state.enter(GameWorld.COLOR) }
    }

    @Test fun unknownAndMalformedDefinitionsDoNotBindTheBlueWing() {
        val rocket = AttributeApplyFixtures.text("attribute-apply-rocket.json")
        assertTrue(AttributeApplyParser.parse("{", "game-color") is AttributeApplyLoad.Rejected)
        assertTrue(AttributeApplyParser.parse(rocket.replace("\"wing\"", "\"nose\""), "game-color") is AttributeApplyLoad.Rejected)
        assertTrue(AttributeApplyParser.parse(rocket.replace("\"blue\"", "\"purple\""), "game-color") is AttributeApplyLoad.Rejected)
        val state = GamesState().enterColor()
        state.bindColor(AttributeApplyFixtures.sun())
        assertEquals(GameColor.YELLOW, state.correctColor)
        assertNotEquals(AttributeApplyFixtures.rocket().lines.success, state.colorSet?.lines?.success)
    }

    @Test fun hubCardOpensOnlyTheBlueSetAndExtrasKeepTheirOwnCorrectColor() {
        val rocket = AttributeApplyFixtures.rocket()
        val sun = AttributeApplyFixtures.sun()
        val packaged = AttributeApplyParser.parse(
            File(repoRoot(), "lessons/attribute-apply-c/experiences/attribute-apply-c.json").readText(),
            "game-color-package-c",
        )
        assertTrue(packaged is AttributeApplyLoad.Ready)
        val green = (packaged as AttributeApplyLoad.Ready).definition
        assertEquals(GameColor.GREEN, green.correct)
        val unordered = listOf(green, sun, rocket)
        assertEquals("game-color", AttributeApplyHub.openFromHub(unordered, "card:1")?.contentId)
        assertNull(AttributeApplyHub.openFromHub(listOf(sun, green), "card:1"))
        val playable = listOf(rocket, sun, green)
        assertEquals("game-color-sun", AttributeApplyHub.openFromHub(playable, "paint")?.contentId)
        assertEquals("game-color-package-c", AttributeApplyHub.openFromHub(playable, "paint:1")?.contentId)
        assertEquals(GameColor.YELLOW, AttributeApplyHub.openFromHub(playable, "paint")?.correct)
        assertEquals(GameColor.GREEN, AttributeApplyHub.openFromHub(playable, "paint:1")?.correct)
    }

    @Test fun installedPackageAppearsAfterReloadAndOpensTheSameColorActivity() {
        val manifest = File(AttributeApplyFixtures.main, "assets/catalog/manifest.json").readText()
        assertFalse(manifest.contains("game-color-package-c"))
        assertFalse(manifest.contains("attribute-apply-c"))
        val reserved = CatalogManifestParser.parse(manifest, ContentSourceKind.BUNDLED).catalog?.items?.map { it.id }?.toSet().orEmpty()
        assertTrue(reserved.contains("game-color"))
        assertTrue(reserved.contains("game-color-sun"))
        val zip = File(repoRoot(), "dist/attribute-apply-c-v1.zip")
        assertTrue("Build lessons/attribute-apply-c before this test: ${zip.absolutePath}", zip.isFile)
        val store = LocalContentPackageStore(
            packagesRoot = tmp.newFolder("pkgs"),
            importRoot = tmp.newFolder("imp"),
            prefs = memoryPrefs(),
            reservedContentIds = { reserved },
        )
        val installed = store.importZip(zip)
        assertTrue(installed.errors.joinToString(), installed.ok)
        assertEquals("attribute-apply-c", installed.packageId)
        val assets = File(AttributeApplyFixtures.main, "assets")
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { path -> File(assets, path).inputStream() },
            catalogRoot = tmp.newFolder("catalog"),
            packageStore = store,
        )
        val catalog = repo.reload()
        assertEquals(ContentSourceKind.MERGED, catalog.source)
        assertFalse(repo.bundledContentIds().contains("game-color-package-c"))
        val item = requireNotNull(repo.getItem("game-color-package-c"))
        assertEquals("attribute-apply-c", item.packageId)
        assertEquals("game/color", item.route)
        assertEquals("ru.familytv.games.ColorVoiceGameActivity", GamesDestination.fromRoute(item.route)?.activityName)
        val hub = File(AttributeApplyFixtures.main, "java/ru/familytv/games/GameHubActivity.kt").readText()
        assertTrue(hub.contains("class ColorVoiceGameActivity : GamesActivity()"))
        val sets = AttributeApplyCatalog.playable(repo)
        assertEquals(listOf("game-color", "game-color-sun", "game-color-package-c"), sets.map { it.contentId })
        assertEquals(GameColor.BLUE, sets[0].correct)
        assertEquals(GameColor.YELLOW, sets[1].correct)
        assertEquals(GameColor.GREEN, sets[2].correct)
        val state = GamesState().enterColor(requireNotNull(AttributeApplyHub.openFromHub(sets, "paint:1")))
        assertEquals("game-color-package-c", state.colorContentId)
        assertEquals(Verdict.WRONG, state.paint(GameColor.BLUE))
        assertEquals(GameColor.BLUE, state.painted)
        assertEquals("Это не зелёный. Крыло ждёт свою краску.", state.prompt())
        assertFalse(state.launch())
        assertTrue(state.help())
        assertEquals(GameColor.BLUE, state.painted)
        assertEquals(Verdict.CORRECT, state.paint(GameColor.GREEN))
        assertEquals("Крыло стало зелёным. Ракета готова к полёту!", state.prompt())
        assertTrue(state.launch())
        val identity = visitIdentity(GameWorld.COLOR, null, sets[2])
        assertEquals("game-color-package-c", identity.contentId)
        val root = JSONObject(manifest)
        val items = root.getJSONArray("items")
        val byId = (0 until items.length()).associate { items.getJSONObject(it).getString("id") to items.getJSONObject(it) }
        assertEquals("attribute-apply-rocket.json", byId.getValue("game-color").getString("experienceRef"))
        assertEquals("attribute-apply-sun.json", byId.getValue("game-color-sun").getString("experienceRef"))
        assertNotEquals(
            byId.getValue("game-color").getString("experienceRef"),
            byId.getValue("game-color-sun").getString("experienceRef"),
        )
    }

    @Test fun runtimeNoLongerOwnsTheBlueWingCopy() {
        val state = File(AttributeApplyFixtures.main, "java/ru/familytv/games/world/GamesState.kt").readText()
        assertFalse(state.contains("Покрась крыло"))
        assertFalse(state.contains("Синее крыло"))
        assertFalse(state.contains("painted != GameColor.BLUE"))
        val activity = File(AttributeApplyFixtures.main, "java/ru/familytv/games/world/GamesActivity.kt").readText()
        assertTrue(activity.contains("AttributeApplyHub.openFromHub"))
        assertTrue(activity.contains("\"card:1\""))
        val navigator = File(AttributeApplyFixtures.main, "java/ru/familytv/home/ContentNavigator.kt").readText()
        assertTrue(navigator.contains("destination == GamesDestination.COLOR"))
    }

    private fun repoRoot(): File = File(AttributeApplyFixtures.main, "../../..").canonicalFile
}
