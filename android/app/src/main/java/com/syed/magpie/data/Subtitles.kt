package com.syed.magpie.data

/**
 * Subtitle files as the Subtitles module sees them: a list of numbered cues
 * whose text can be swapped out, and everything else kept byte for byte.
 *
 * Pure Kotlin on purpose — no Android types — so the parser, the batching and
 * the acceptance check run under plain JUnit against real films.
 */
enum class SubFormat(val ext: String, val mime: String) {
    SRT("srt", "application/x-subrip"),
    VTT("vtt", "text/vtt"),
    ASS("ass", "text/x-ssa");

    companion object {
        fun of(fileName: String): SubFormat = when (fileName.substringAfterLast('.', "").lowercase()) {
            "ass", "ssa" -> ASS
            "vtt" -> VTT
            else -> SRT
        }
    }
}

/**
 * One line of dialogue. [n] is its 1-based position in the file and is what
 * travels to the model and back; the timing never leaves the phone.
 */
data class Cue(val n: Int, val text: String)

/** A parsed file, able to write itself back out with some cues replaced. */
class SubtitleFile private constructor(
    val format: SubFormat,
    val cues: List<Cue>,
    private val crlf: Boolean,
    /** SRT/VTT: header and per-cue frames. ASS: every line, dialogue included. */
    private val frames: List<Frame>,
) {
    private sealed interface Frame {
        data class Verbatim(val text: String) : Frame
        /** [before] is everything up to the text: index, timing, or the ASS prefix. */
        data class Slot(val n: Int, val before: String) : Frame
    }

    val count: Int get() = cues.size

    /**
     * The file with [replacements] applied by cue number. Line endings come
     * back as they were, so a Windows-style file stays one.
     */
    fun render(replacements: Map<Int, String>): String {
        val byN = cues.associateBy { it.n }
        val out = StringBuilder()
        when (format) {
            SubFormat.ASS -> frames.forEach { f ->
                when (f) {
                    is Frame.Verbatim -> out.append(f.text)
                    is Frame.Slot -> out.append(f.before).append(replacements[f.n] ?: byN.getValue(f.n).text)
                }
                out.append('\n')
            }
            SubFormat.SRT, SubFormat.VTT -> frames.forEachIndexed { i, f ->
                when (f) {
                    is Frame.Verbatim -> out.append(f.text)
                    is Frame.Slot -> out.append(f.before).append(replacements[f.n] ?: byN.getValue(f.n).text)
                }
                if (i < frames.lastIndex) out.append("\n\n")
            }
        }
        if (format != SubFormat.ASS) out.append('\n')
        val text = out.toString()
        return if (crlf) text.replace("\n", "\r\n") else text
    }

    companion object {
        fun parse(content: String, format: SubFormat): SubtitleFile {
            // Mixed endings are common in files that passed through several
            // tools; they all become \n here and go back out as the majority.
            val crlf = content.count { it == '\r' } * 2 > content.count { it == '\n' }
            val text = content.replace("\r\n", "\n").replace('\r', '\n')
            return when (format) {
                SubFormat.SRT -> parseBlocks(text, crlf, SubFormat.SRT, header = null)
                SubFormat.VTT -> parseVtt(text, crlf)
                SubFormat.ASS -> parseAss(text, crlf)
            }
        }

        private fun parseVtt(text: String, crlf: Boolean): SubtitleFile {
            val nl = text.indexOf("\n\n")
            val header = if (text.startsWith("WEBVTT")) {
                if (nl < 0) text.trimEnd() else text.substring(0, nl)
            } else null
            val body = if (header == null) text else if (nl < 0) "" else text.substring(nl + 2)
            return parseBlocks(body, crlf, SubFormat.VTT, header)
        }

        /** SRT and VTT share a shape: blank-line separated blocks with a timing line. */
        private fun parseBlocks(text: String, crlf: Boolean, format: SubFormat, header: String?): SubtitleFile {
            val frames = mutableListOf<Frame>()
            val cues = mutableListOf<Cue>()
            header?.let { frames += Frame.Verbatim(it) }
            var n = 0
            for (block in text.trim().split(Regex("\n[ \t]*\n"))) {
                val raw = block.trim('\n')
                if (raw.isBlank()) continue
                val lines = raw.split('\n')
                val timingAt = lines.indexOfFirst { it.contains("-->") }
                val body = if (timingAt < 0) "" else lines.drop(timingAt + 1).joinToString("\n").trim()
                if (timingAt < 0 || body.isEmpty()) {
                    // A NOTE block, a bare index, or a cue with no text: kept as is.
                    frames += Frame.Verbatim(raw)
                    continue
                }
                n++
                val before = lines.take(timingAt + 1).joinToString("\n") + "\n"
                frames += Frame.Slot(n, before)
                cues += Cue(n, body)
            }
            return SubtitleFile(format, cues, crlf, frames)
        }

        private fun parseAss(text: String, crlf: Boolean): SubtitleFile {
            val frames = mutableListOf<Frame>()
            val cues = mutableListOf<Cue>()
            var n = 0
            for (line in text.trimEnd('\n').split('\n')) {
                // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                // Text is the tenth field and may itself contain commas.
                val fields = if (line.startsWith("Dialogue:")) line.split(",", limit = 10) else emptyList()
                if (fields.size < 10 || fields[9].isBlank()) {
                    frames += Frame.Verbatim(line)
                    continue
                }
                n++
                val before = line.substring(0, line.length - fields[9].length)
                frames += Frame.Slot(n, before)
                cues += Cue(n, fields[9])
            }
            return SubtitleFile(SubFormat.ASS, cues, crlf, frames)
        }
    }
}

object Subtitles {

    /**
     * The colour the Bengali brackets come back in. The dialogue is white, so
     * a warm accent carries the meaning without competing for the same
     * pixels: yellow is the classic second-subtitle colour, readable at a
     * glance while a line is on screen. Off leaves the file plain, for
     * players that would print the tags as text.
     */
    enum class HintColor(val hex: String, val label: String) {
        YELLOW("FFFF00", "Yellow"),
        GOLD("FFC400", "Gold"),
        CYAN("00E5FF", "Cyan"),
        OFF("", "Off");

        val on: Boolean get() = hex.isNotEmpty()

        /** ARGB for a swatch or a Compose preview of this colour. */
        val argb: Long get() = if (on) 0xFF000000L or hex.toLong(16) else 0L

        companion object {
            fun of(name: String?): HintColor = entries.firstOrNull { it.name == name } ?: CYAN
        }
    }

    /**
     * Wraps every Bengali bracket in a colour tag the file's format can carry.
     * SRT and VTT have no colour of their own, but every player that matters
     * honours the HTML font tag inside them; ASS has a native override, and
     * gets white restored after it, since `{\r}` would also undo the italics.
     */
    fun colorize(line: String, color: HintColor, format: SubFormat): String {
        if (!color.on) return line
        return when (format) {
            SubFormat.SRT, SubFormat.VTT ->
                Hints.hintRegex.replace(line) { wrap(it, "<font color=\"#${color.hex}\">", "</font>") }
            SubFormat.ASS -> {
                // ASS wants BGR, not RGB: FF C4 00 becomes 00 C4 FF.
                val bgr = color.hex.chunked(2).reversed().joinToString("")
                Hints.hintRegex.replace(line) { wrap(it, "{\\c&H$bgr&}", "{\\c&HFFFFFF&}") }
            }
        }
    }

    /** Tags around the brackets, with any leading whitespace kept outside. */
    private fun wrap(match: MatchResult, open: String, close: String): String {
        val text = match.value
        val lead = text.takeWhile { it.isWhitespace() }
        return lead + open + text.drop(lead.length) + close
    }

    /** Lines per request the screen offers; the middle one is the default. */
    val BATCH_SIZES = listOf(100, 250, 400)
    const val DEFAULT_BATCH = 250

    /**
     * Characters of dialogue per request. Cue counts say little about size —
     * a talky drama and an action film differ threefold — so this is the cap
     * that actually holds a reply to a size the model keeps straight.
     */
    const val MAX_CHARS = 12_000

    /** The saved file's name: the source's, marked before its extension. */
    fun outputName(sourceName: String): String {
        val dot = sourceName.lastIndexOf('.')
        return if (dot > 0) sourceName.substring(0, dot) + "_translated" + sourceName.substring(dot)
        else "${sourceName}_translated"
    }

    /** Where the batches fall for cues of these lengths, closing one at either cap. */
    fun cuts(lengths: List<Int>, maxLines: Int, maxChars: Int = MAX_CHARS): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        var chars = 0
        lengths.forEachIndexed { i, len ->
            val size = i - start
            if (size > 0 && (size >= maxLines || chars + len > maxChars)) {
                out += start until i
                start = i
                chars = 0
            }
            chars += len
        }
        if (start < lengths.size) out += start until lengths.size

        // A tail of a few lines would cost a whole request of its own, most
        // of it the prompt; fold it into the batch before when the character
        // cap allows. 251 lines at 250 a request is one request, not two.
        if (out.size >= 2) {
            val tail = out.last()
            val prev = out[out.size - 2]
            val tailSize = tail.last - tail.first + 1
            val chars = (prev.first..tail.last).sumOf { lengths[it] }
            if (tailSize <= maxOf(1, maxLines / 10) && chars <= maxChars) {
                out.removeAt(out.lastIndex)
                out[out.lastIndex] = prev.first..tail.last
            }
        }
        return out
    }

    /** Groups cues in file order, closing a batch at either cap. */
    fun batches(cues: List<Cue>, maxLines: Int, maxChars: Int = MAX_CHARS): List<List<Cue>> =
        cuts(cues.map { it.text.length }, maxLines, maxChars).map { cues.subList(it.first, it.last + 1) }
}

/**
 * The acceptance check for a returned line.
 *
 * The prompt asks the model to change nothing but add Bengali in brackets;
 * this is where that is enforced rather than trusted. Take the brackets out
 * and what is left must be the original line, or the original stays. A merged
 * pair of cues, a dropped tag, a "corrected" quote mark — all fail here and
 * cost one cue, never a batch.
 */
object Hints {
    /** A bracketed hint: parentheses holding at least one Bengali letter. */
    val hintRegex: Regex = Regex("""\s*\((?=[^()]*[ঀ-৿])[^()]*\)""")

    /**
     * Colour markup [Subtitles.colorize] adds, so a coloured file can be read
     * — or sent round again — as its plain self. Only font tags and ASS colour
     * overrides go: `{\i1}` and friends must survive.
     */
    // Every brace escaped: Android's ICU regex engine refuses a bare `}`
    // that the desktop JVM accepts, and tests run on the JVM.
    private val COLOR_TAGS = Regex("""</?font[^>]*>|\{\\(?:1c|c)&H[0-9A-Fa-f]{6}&\}""")

    private val SPACES = Regex("""[ \t]+""")
    private val AROUND_BREAKS = Regex(""" ?\n ?""")

    fun hasHint(line: String): Boolean = hintRegex.containsMatchIn(line)

    fun stripped(line: String): String = line.replace(COLOR_TAGS, "").replace(hintRegex, "")

    /** Whitespace-insensitive form; the model may drop a double space or a stray CR. */
    fun normal(line: String): String =
        line.replace("\r", "").replace(SPACES, " ").replace(AROUND_BREAKS, "\n").trim()

    /** True when [returned] is [original] plus hints and nothing else. */
    fun accepts(original: String, returned: String): Boolean =
        normal(stripped(returned)) == normal(original)

    /** Bengali letters in a line, counted as words for the library's meta line. */
    fun hintCount(line: String): Int = hintRegex.findAll(line).count()
}
