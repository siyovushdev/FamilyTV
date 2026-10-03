package ru.familytv.games.world

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
import ru.familytv.games.CountCombineCatalog
import ru.familytv.games.CountCombineHub
import ru.familytv.games.CountCombineLoad
import ru.familytv.games.CountCombineParser
import ru.familytv.games.GameColor
import ru.familytv.games.Verdict

/**
 * Curriculum checks for packaged Games content.
 * The orchard only composes 1–3 apples on a side, so shipped rounds stay inside that picture.
 */
class GamesContentPackTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun shippedMathSetsStayDrawableDistinctAndAddressed() {
        val manifest = manifestText()
        val pairsById = linkedMapOf<String, List<Pair<Int, Int>>>()
        mathExperienceFiles().groupBy { JSONObject(it.readText()).getString("contentId") }.forEach { (id, files) ->
            val text = files.first().readText()
            files.drop(1).forEach { copy ->
                assertEquals("$id lesson copy differs from ${files.first().name}", text, copy.readText())
            }
            val root = JSONObject(text)
            assertEquals("count_combine", root.getString("template"))
            val ready = CountCombineParser.parse(text, id)
            assertTrue("$id ${ready}", ready is CountCombineLoad.Ready)
            val set = (ready as CountCombineLoad.Ready).definition
            assertTrue(set.rounds.isNotEmpty())
            set.pairs.forEach { (left, right) ->
                assertTrue("$id basket $left+$right is outside the drawn 1..3", left in 1..3 && right in 1..3)
            }
            assertEquals(set.pairs.distinct().size, set.pairs.size)
            assertTrue(set.lines.separate.isNotBlank() && set.lines.ask.isNotBlank())
            assertTrue(set.lines.help.isNotBlank() && set.lines.wrong.isNotBlank() && set.lines.success.isNotBlank())
            assertTrue(set.countingWords.size >= set.pairs.maxOf { it.first + it.second })
            assertTrue(CountCombineParser.parse(text, "game-math") is CountCombineLoad.Rejected || id == "game-math")
            pairsById[id] = set.pairs
            if (id !in BUNDLED_MATH) assertFalse(manifest.contains("\"$id\""))
        }
        assertEquals(TINY, pairsById["game-math-tiny"])
        assertEquals(TO_FIVE, pairsById["game-math-to-five"])
        assertEquals(MANY, pairsById["game-math-many"])
        assertNotEquals(pairsById["game-math"], pairsById["game-math-tiny"])
        assertNotEquals(pairsById["game-math"], pairsById["game-math-many"])
        assertTrue(pairsById.values.flatten().contains(3 to 3))
        assertTrue(pairsById.values.flatten().contains(2 to 1))
        val rounds = pairsById.values.sumOf { it.size }
        assertTrue("expected about 20–30 rounds, was $rounds", rounds in 20..30)
    }

    @Test fun shippedColorSetsUseOnlyTheKnownWingColors() {
        val manifest = manifestText()
        val correct = linkedMapOf<String, GameColor>()
        colorExperienceFiles().forEach { file ->
            val root = JSONObject(file.readText())
            val id = root.getString("contentId")
            assertEquals("attribute_apply", root.getString("template"))
            assertEquals("rocket", root.getJSONObject("target").getString("object"))
            assertEquals("wing", root.getJSONObject("target").getString("part"))
            val ready = AttributeApplyParser.parse(file.readText(), id)
            assertTrue("$id ${ready}", ready is AttributeApplyLoad.Ready)
            val set = (ready as AttributeApplyLoad.Ready).definition
            assertEquals(3, set.options.size)
            assertTrue(set.lines.prompt.isNotBlank() && set.lines.help.isNotBlank())
            assertTrue(set.lines.wrong.isNotBlank() && set.lines.success.isNotBlank() && set.lines.launched.isNotBlank())
            correct[id] = set.correct
            if (id !in BUNDLED_COLOR) {
                assertFalse(manifest.contains("\"$id\""))
                assertTrue(AttributeApplyParser.parse(file.readText(), "game-color") is AttributeApplyLoad.Rejected)
            }
        }
        assertEquals(setOf(GameColor.BLUE, GameColor.YELLOW, GameColor.GREEN, GameColor.RED), correct.values.toSet())
        assertEquals(GameColor.RED, correct["game-color-red"])
        assertNotEquals(correct["game-color"], correct["game-color-red"])
    }

    @Test fun newPackagesAppearAfterReloadWithoutReplacingBundledSets() {
        val manifest = manifestText()
        val reserved = CatalogManifestParser.parse(manifest, ContentSourceKind.BUNDLED).catalog?.items?.map { it.id }?.toSet().orEmpty()
        listOf("game-math-tiny", "game-math-to-five", "game-math-many", "game-color-red").forEach {
            assertFalse(reserved.contains(it))
        }
        val store = LocalContentPackageStore(
            packagesRoot = tmp.newFolder("pkgs"),
            importRoot = tmp.newFolder("imp"),
            prefs = memoryPrefs(),
            reservedContentIds = { reserved },
        )
        NEW_ZIPS.forEach { name ->
            val zip = File(repoRoot(), "dist/$name")
            assertTrue("Build lessons before this test: ${zip.path}", zip.isFile)
            val installed = store.importZip(zip)
            assertTrue(installed.errors.joinToString(), installed.ok)
        }
        val assets = File(CountCombineFixtures.main, "assets")
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { path -> File(assets, path).inputStream() },
            catalogRoot = tmp.newFolder("catalog"),
            packageStore = store,
        )
        assertEquals(ContentSourceKind.MERGED, repo.reload().source)
        val math = CountCombineCatalog.playable(repo)
        assertEquals(
            listOf("game-math", "game-math-baskets", "game-math-tiny", "game-math-to-five", "game-math-many"),
            math.map { it.contentId },
        )
        assertEquals("game-math", CountCombineHub.openFromHub(math, "card:0")?.contentId)
        assertEquals(TINY, CountCombineHub.openFromHub(math, "more:1")?.pairs)
        assertEquals(MANY, CountCombineHub.openFromHub(math, "more:3")?.pairs)
        val tiny = GamesState().enterMath(requireNotNull(CountCombineHub.openFromHub(math, "more:1")))
        tiny.beginCombine(); tiny.finishCombine()
        assertEquals(Verdict.WRONG, tiny.answerMath(1))
        assertEquals(1 to 1, tiny.apples.left to tiny.apples.right)
        assertTrue(tiny.help())
        assertEquals(1 to 1, tiny.apples.left to tiny.apples.right)
        tiny.finishCounting()
        assertEquals(Verdict.CORRECT, tiny.answerMath(2))
        assertEquals("game-math-tiny", visitIdentity(GameWorld.MATH, tiny.mathSet).contentId)
        val many = GamesState().enterMath(requireNotNull(CountCombineHub.openFromHub(math, "more:3")))
        many.beginCombine(); many.finishCombine()
        assertEquals(Verdict.WRONG, many.answerMath(5))
        assertEquals(3 to 3, many.apples.left to many.apples.right)
        assertEquals(Verdict.CORRECT, many.answerMath(6))
        assertEquals("game-math-many", many.mathContentId)
        val color = AttributeApplyCatalog.playable(repo)
        assertEquals(listOf("game-color", "game-color-sun", "game-color-red"), color.map { it.contentId })
        assertEquals(GameColor.BLUE, AttributeApplyHub.openFromHub(color, "card:1")?.correct)
        val red = GamesState().enterColor(requireNotNull(AttributeApplyHub.openFromHub(color, "paint:1")))
        assertEquals(GameColor.RED, red.correctColor)
        assertEquals(Verdict.WRONG, red.paint(GameColor.BLUE))
        assertEquals(GameColor.BLUE, red.painted)
        assertTrue(red.help())
        assertEquals(GameColor.BLUE, red.painted)
        assertFalse(red.launch())
        assertEquals(Verdict.CORRECT, red.paint(GameColor.RED))
        assertFalse(red.launched)
        assertTrue(red.launch())
        assertEquals("game-color-red", visitIdentity(GameWorld.COLOR, null, red.colorSet).contentId)
    }

    private fun manifestText() = File(CountCombineFixtures.main, "assets/catalog/manifest.json").readText()

    private fun repoRoot(): File = File(CountCombineFixtures.main, "../../..").canonicalFile

    private fun mathExperienceFiles(): List<File> {
        val bundled = File(CountCombineFixtures.main, "assets/catalog/experiences")
            .listFiles { file -> file.name.startsWith("count-combine") && file.extension == "json" }
            .orEmpty()
            .toList()
        val packaged = File(repoRoot(), "lessons").listFiles().orEmpty().flatMap { lesson ->
            File(lesson, "experiences").listFiles { file ->
                file.name.startsWith("count-combine") && file.extension == "json"
            }?.toList().orEmpty()
        }
        return (bundled + packaged).sortedBy { it.name }
    }

    private fun colorExperienceFiles(): List<File> {
        val bundled = File(CountCombineFixtures.main, "assets/catalog/experiences")
            .listFiles { file -> file.name.startsWith("attribute-apply") && file.extension == "json" }
            .orEmpty()
            .toList()
        val packaged = File(repoRoot(), "lessons").listFiles().orEmpty().flatMap { lesson ->
            File(lesson, "experiences").listFiles { file ->
                file.name.startsWith("attribute-apply") && file.extension == "json"
            }?.toList().orEmpty()
        }
        return (bundled + packaged).sortedBy { it.name }
    }

    private companion object {
        val BUNDLED_MATH = setOf("game-math", "game-math-baskets")
        val BUNDLED_COLOR = setOf("game-color", "game-color-sun")
        val TINY = listOf(1 to 1, 2 to 1, 1 to 2)
        val TO_FIVE = listOf(2 to 2, 1 to 3, 3 to 1, 2 to 3, 3 to 2)
        val MANY = listOf(3 to 3, 3 to 2, 2 to 3, 3 to 1)
        val NEW_ZIPS = listOf(
            "count-combine-tiny-v1.zip",
            "count-combine-to-five-v1.zip",
            "count-combine-many-v1.zip",
            "attribute-apply-red-v1.zip",
        )
    }
}
