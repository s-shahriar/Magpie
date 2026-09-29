package com.syed.magpie.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The real thing, on the desktop: cues 801–1200 of a film through the same
 * client, prompt and acceptance check the app ships. Runs only when
 * GEMINI_API_KEY and MAGPIE_SRT are set, so an ordinary test run costs no
 * requests.
 *
 *   GEMINI_API_KEY=… MAGPIE_SRT=/path/film.srt ./gradlew :app:testDebugUnitTest --tests '*GeminiLiveTest*' -i
 */
class GeminiLiveTest {

    @Test
    fun `cues 801 to 1200 come back numbered and pass the acceptance check`() {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        val path = System.getenv("MAGPIE_SRT").orEmpty()
        assumeTrue("set GEMINI_API_KEY and MAGPIE_SRT to run this", key.isNotBlank() && File(path).exists())

        val model = Gemini.Model.of(System.getenv("MAGPIE_MODEL"))
        val file = SubtitleFile.parse(File(path).readText(), SubFormat.of(path))
        val slice = file.cues.filter { it.n in 801..1200 }
        val batches = Subtitles.batches(slice, Subtitles.DEFAULT_BATCH)

        var accepted = 0
        var hinted = 0
        var missing = 0
        var refused = 0
        val out = StringBuilder()
        val hints = LinkedHashMap<Int, String>()
        batches.forEachIndexed { i, batch ->
            val reply = Gemini.annotate(key, model, batch)
            println("batch ${i + 1}/${batches.size}: ${reply.lines.size}/${batch.size} lines back in ${reply.millis} ms, " +
                "prompt=${reply.promptTokens} resp=${reply.responseTokens}")
            for (cue in batch) {
                val back = reply.lines[cue.n]
                when {
                    back == null -> { missing++; println("  missing #${cue.n}: ${cue.text.replace("\n", " / ")}") }
                    !Hints.accepts(cue.text, back) -> { refused++; println("  refused #${cue.n}: ${back.replace("\n", " / ")}") }
                    else -> {
                        accepted++
                        if (Hints.hasHint(back)) { hinted++; hints[cue.n] = back.replace("\r", ""); out.append("#${cue.n} ").append(back.replace("\n", " / ")).append('\n') }
                    }
                }
            }
            if (i < batches.lastIndex) Thread.sleep(4_000)
        }
        println("accepted=$accepted hinted=$hinted missing=$missing refused=$refused of ${slice.size}")

        // With MAGPIE_BASE (an earlier output of the same film) and MAGPIE_OUT
        // set, fold this slice's hints into that file: the way a resumed job
        // completes a film without asking for its finished batches again.
        val base = System.getenv("MAGPIE_BASE").orEmpty()
        val dest = System.getenv("MAGPIE_OUT").orEmpty()
        if (dest.isNotBlank()) {
            val baseFile = base.takeIf { File(it).exists() }?.let { SubtitleFile.parse(File(it).readText(), SubFormat.of(it)) } ?: file
            check(baseFile.count == file.count) { "base file has a different cue count" }
            val merged = baseFile.cues.associate { it.n to it.text }.toMutableMap()
            hints.forEach { (n, t) -> merged[n] = t }
            File(dest).writeText(file.render(merged))
            println("wrote $dest with ${merged.count { Hints.hasHint(it.value) }} hinted cues")
        }
        println(out)
        assertTrue("no hints came back", hinted > 0)
        assertTrue("too many lines lost", missing + refused < slice.size / 10)
    }
}
