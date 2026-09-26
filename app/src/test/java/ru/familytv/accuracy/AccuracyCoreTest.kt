package ru.familytv.accuracy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AccuracyCoreTest {
    private val root = File("/tmp/accuracy-parser-test")
    private fun row(extra: String = "") = """{"id":"sample_1","file":"audio/a.wav","truth":"шесть","category":"number"$extra}"""
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun datasetParsesMetadataAndRejectsTraversalDuplicatesAndAnswerOnlyVocabulary() {
        val sample = AccuracyDataset.parse(row(",\"speaker\":\"adult\",\"futureField\":{\"x\":1}"), root).single()
        assertEquals("6", sample.answerTruth); assertEquals(AnswerDomain.NUMBER_0_10, sample.domain)
        assertEquals(1, sample.metadata.getJSONObject("futureField").getInt("x"))
        rejects { AccuracyDataset.parse(row() + "\n" + row(), root) }
        rejects { AccuracyDataset.parse(row().replace("audio/a.wav", "../a.wav"), root) }
        rejects { AccuracyDataset.parse(row(",\"allowed\":[\"шесть\"]"), root) }
        rejects { AccuracyDataset.parse("{}", root) }
        rejects { AccuracyDataset.parse(row(",\"answerTruth\":\"11\""), root) }
    }
    @Test fun contextIsIndependentOfReferenceTruthAndAlwaysWholeDomain() {
        val six = AccuracyDataset.parse(row(",\"expectedAnswer\":\"secret-six\""), root).single()
        val one = AccuracyDataset.parse(row().replace("шесть", "один"), root).single()
        assertEquals(six.domain.hotwords(), one.domain.hotwords())
        assertEquals(11, six.domain.hotwords().split('/').size)
        assertEquals(4, AnswerDomain.COLOR.words.size)
        assertFalse(AnswerPolicy.domainInstruction(six.domain).contains("secret-six"))
        assertEquals(AnswerPolicy.domainInstruction(six.domain), AnswerPolicy.domainInstruction(one.domain))
    }
    @Test fun unknownPolicyRejectsAcousticGuessesAndCrossDomainHallucinations() {
        val n = AnswerDomain.NUMBER_0_10; val c = AnswerDomain.COLOR
        assertFalse(AnswerPolicy.mayNormalize("честь", n)); assertFalse(AnswerPolicy.mayNormalize("ты", c))
        for ((speech, output, domain) in listOf(Triple("ты", "2", n), Triple("честь", "зелёный", c),
            Triple("честь", "6", n), Triple("четыре", "5", n), Triple("я выбираю синий", "красный", c))) {
            assertTrue(AnswerPolicy.validate(speech, output, domain) is AnswerRecognitionResult.Unknown)
        }
        assertEquals("5", AnswerPolicy.value(AnswerPolicy.validate("мне кажется пят", "5", n)))
        assertEquals("6", AnswerPolicy.value(AnswerPolicy.validate("не пят а шесть", "6", n)))
        assertEquals("синий", AnswerPolicy.value(AnswerPolicy.validate("я выбираю синий", "синий", c)))
        assertEquals("UNKNOWN", AnswerPolicy.value(AnswerPolicy.validate("пять", "UNKNOWN", n)))
        assertNull((AnswerPolicy.literal("шесть", n) as AnswerRecognitionResult.Recognized).confidence)
    }
    @Test fun editDistanceTracksSubstitutionsInsertionsDeletionsAndEmptyReferences() {
        assertEquals(WordErrors(), AccuracyMetrics.errors("Шесть!", "шесть"))
        assertEquals(WordErrors(substitutions = 1), AccuracyMetrics.errors("шесть", "честь"))
        assertEquals(WordErrors(deletions = 1), AccuracyMetrics.errors("мой ответ пять", "ответ пять"))
        assertEquals(WordErrors(insertions = 2), AccuracyMetrics.errors("", "два слова"))
        assertEquals(WordErrors(insertions = 1), AccuracyMetrics.errors("синий", "синий синий"))
        assertEquals(2.5, AccuracyMetrics.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 0.0)
        assertEquals(19.0, AccuracyMetrics.p95((1..20).map(Int::toDouble))!!, 0.0)
        assertNull(AccuracyMetrics.median(emptyList()))
    }
    @Test fun waveRoundTripRejectsStereoAndTruncation() {
        val file = Files.createTempFile("accuracy-wave", ".wav").toFile()
        try {
            val pcm = shortArrayOf(0, 20, -50, 32767, -32768)
            WaveFiles.write(file, pcm); assertArrayEquals(pcm, WaveFiles.read(file).pcm)
            val bytes = file.readBytes(); bytes[22] = 2; file.writeBytes(bytes)
            rejects { WaveFiles.read(file) }
            file.writeBytes(bytes.copyOf(20)); rejects { WaveFiles.read(file) }
        } finally { file.delete() }
    }
    @Test fun emptyCorpusProducesNoFabricatedAccuracyAndMatrixHasSixConfigurations() {
        val dir = Files.createTempDirectory("accuracy-reports").toFile()
        try {
            AccuracyMetrics.write(dir, emptyList(), JSONObject().put("sampleCount", 0))
            assertTrue(File(dir, "summary.md").readText().contains("No scored samples"))
            assertEquals(0, JSONObject(File(dir, "summary.json").readText()).getJSONObject("configs").length())
            assertEquals(listOf(.5f, 1f, 1.5f, 2f), AsrExperiment.matrix.mapNotNull { it.score })
        } finally { dir.deleteRecursively() }
    }
    @Test fun aggregateKeepsFailuresInDenominatorAndDoesNotCreditErrorAbstentions() {
        fun row(truth: String, text: String, answer: String, actual: String, failed: Boolean): JSONObject {
            val e = AccuracyMetrics.errors(truth, text)
            return JSONObject().put("truth", truth).put("exact", truth == text && !failed)
                .put("answerTruth", answer).put("literalAnswer", actual).put("sttMs", 20.0)
                .put("wordErrors", e.total).put("substitutions", e.substitutions)
                .put("deletions", e.deletions).put("insertions", e.insertions)
                .put("error", if (failed) "decode_failed" else JSONObject.NULL)
        }
        val a = AccuracyMetrics.aggregate(listOf(row("шесть", "честь", "6", "UNKNOWN", false),
            row("синий", "синий", "синий", "синий", false), row("не знаю", "", "UNKNOWN", "UNKNOWN", true)))
        assertEquals(1, a.getInt("rawExact")); assertEquals(.75, a.getDouble("wer"), 0.0)
        assertEquals(1, a.getInt("failures"))
        assertEquals(1, a.getJSONObject("literal").getInt("correct"))
        assertEquals(3, a.getJSONObject("literal").getInt("n"))
        assertTrue(a.isNull("guardedQwen"))
    }
    @Test fun manifestAcceptanceAppendsWithoutOverwritingEarlierSamples() {
        val dir = Files.createTempDirectory("accuracy-accept").toFile()
        try {
            AccuracyDataset.append(dir, JSONObject(row()))
            AccuracyDataset.append(dir, JSONObject(row().replace("sample_1", "sample_2")))
            assertEquals(listOf("sample_1", "sample_2"), AccuracyDataset.load(dir).map { it.id })
            rejects { AccuracyDataset.append(dir, JSONObject(row())) }
            assertEquals(2, AccuracyDataset.load(dir).size)
        } finally { dir.deleteRecursively() }
    }

}
