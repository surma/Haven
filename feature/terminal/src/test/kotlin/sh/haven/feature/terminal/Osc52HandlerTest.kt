package sh.haven.feature.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Base64

class Osc52HandlerTest {

    private lateinit var handler: Osc52Handler
    private val clipboardResults = mutableListOf<String>()

    @Before
    fun setUp() {
        clipboardResults.clear()
        handler = Osc52Handler { text -> clipboardResults.add(text) }
    }

    private fun process(input: ByteArray): ByteArray {
        val output = ByteArray(input.size + 1024) // extra room for flushed sequences
        val len = handler.process(input, 0, input.size, output)
        return output.copyOfRange(0, len)
    }

    private fun process(input: String): ByteArray = process(input.toByteArray())

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    // --- BEL terminator ---

    @Test
    fun `complete OSC 52 with BEL terminator sets clipboard`() {
        val b64 = encode("Hello from remote!")
        val input = "\u001b]52;c;$b64\u0007"
        val output = process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("Hello from remote!", clipboardResults[0])
        assertEquals(0, output.size) // sequence fully stripped
    }

    // --- ST terminator ---

    @Test
    fun `complete OSC 52 with ST terminator sets clipboard`() {
        val b64 = encode("ST terminated")
        val input = "\u001b]52;c;$b64\u001b\\"
        val output = process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("ST terminated", clipboardResults[0])
        assertEquals(0, output.size)
    }

    // --- Passthrough ---

    @Test
    fun `non-OSC data passes through unchanged`() {
        val input = "Hello, World!\r\n"
        val output = process(input)
        assertEquals(input, String(output))
        assertEquals(0, clipboardResults.size)
    }

    @Test
    fun `regular escape sequences pass through`() {
        // CSI sequence (cursor movement) should pass through
        val input = "\u001b[1;1H"
        val output = process(input)
        assertEquals(input, String(output))
    }

    @Test
    fun `OSC but not 52 passes through`() {
        // OSC 0 (set title) should pass through
        val input = "\u001b]0;my title\u0007"
        val output = process(input)
        assertEquals(input, String(output))
    }

    // --- Mixed data ---

    @Test
    fun `OSC 52 surrounded by normal data`() {
        val b64 = encode("clip")
        val input = "before\u001b]52;c;$b64\u0007after"
        val output = process(input)

        assertEquals("beforeafter", String(output))
        assertEquals(1, clipboardResults.size)
        assertEquals("clip", clipboardResults[0])
    }

    @Test
    fun `multiple OSC 52 sequences in one buffer`() {
        val b64a = encode("first")
        val b64b = encode("second")
        val input = "\u001b]52;c;$b64a\u0007\u001b]52;c;$b64b\u0007"
        val output = process(input)

        assertEquals(0, output.size)
        assertEquals(2, clipboardResults.size)
        assertEquals("first", clipboardResults[0])
        assertEquals("second", clipboardResults[1])
    }

    // --- Partial sequences across buffers ---

    @Test
    fun `OSC 52 split across two buffers`() {
        val b64 = encode("split test")
        val full = "\u001b]52;c;$b64\u0007"
        val mid = full.length / 2
        val part1 = full.substring(0, mid).toByteArray()
        val part2 = full.substring(mid).toByteArray()

        val out1 = process(part1)
        assertEquals(0, out1.size) // buffered, waiting for terminator
        assertEquals(0, clipboardResults.size)

        val out2 = process(part2)
        assertEquals(0, out2.size)
        assertEquals(1, clipboardResults.size)
        assertEquals("split test", clipboardResults[0])
    }

    @Test
    fun `ST terminator split across buffers - ESC in first, backslash in second`() {
        val b64 = encode("st split")
        val part1 = "\u001b]52;c;$b64\u001b".toByteArray()
        val part2 = "\\".toByteArray()

        val out1 = process(part1)
        assertEquals(0, out1.size)
        assertEquals(0, clipboardResults.size)

        val out2 = process(part2)
        assertEquals(0, out2.size)
        assertEquals(1, clipboardResults.size)
        assertEquals("st split", clipboardResults[0])
    }

    // --- Invalid sequences ---

    @Test
    fun `incomplete OSC 52 prefix flushes to output`() {
        // ESC ] 5 followed by non-'2' — should flush
        val input = "\u001b]5X rest"
        val output = process(input)
        assertEquals(input, String(output))
        assertEquals(0, clipboardResults.size)
    }

    @Test
    fun `ESC followed by non-bracket flushes`() {
        val input = "\u001bA normal text"
        val output = process(input)
        assertEquals(input, String(output))
    }

    // --- Empty data (query) ---

    @Test
    fun `empty data payload is ignored - query request`() {
        // Empty base64 data = query; should not call callback
        val input = "\u001b]52;c;\u0007"
        val output = process(input)

        assertEquals(0, output.size) // sequence still stripped
        assertEquals(0, clipboardResults.size)
    }

    // --- Selection parameter variants ---

    @Test
    fun `selection parameter p works`() {
        val b64 = encode("primary")
        val input = "\u001b]52;p;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("primary", clipboardResults[0])
    }

    @Test
    fun `empty selection parameter works`() {
        val b64 = encode("no sel")
        val input = "\u001b]52;;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals("no sel", clipboardResults[0])
    }

    // --- 1 MB cap ---

    @Test
    fun `payload exceeding 1MB cap aborts and flushes`() {
        // Create a payload larger than 1MB
        val bigData = ByteArray(Osc52Handler.MAX_PAYLOAD_BYTES + 100) { 'A'.code.toByte() }
        val prefix = "\u001b]52;c;".toByteArray()
        val suffix = byteArrayOf(0x07)

        val input = prefix + bigData + suffix
        val output = ByteArray(input.size + 1024)
        val outLen = handler.process(input, 0, input.size, output)

        // The handler should have aborted — no clipboard set
        assertEquals(0, clipboardResults.size)
        // Some bytes should have been flushed to output
        assert(outLen > 0) { "Expected flushed bytes in output" }
    }

    // --- Offset parameter ---

    @Test
    fun `offset parameter is respected`() {
        val b64 = encode("offset test")
        val payload = "\u001b]52;c;$b64\u0007".toByteArray()
        val padded = ByteArray(10) { 0x41 } + payload // 10 bytes of 'A' prefix
        val output = ByteArray(padded.size + 1024)

        val outLen = handler.process(padded, 10, payload.size, output)

        assertEquals(0, outLen) // OSC 52 stripped
        assertEquals(1, clipboardResults.size)
        assertEquals("offset test", clipboardResults[0])
    }

    // --- Unicode ---

    @Test
    fun `UTF-8 encoded payload decodes correctly`() {
        val text = "Hello \u00e9\u00e0\u00fc \u2603 \u2764"
        val b64 = encode(text)
        val input = "\u001b]52;c;$b64\u0007"
        process(input)

        assertEquals(1, clipboardResults.size)
        assertEquals(text, clipboardResults[0])
    }

    // --- Normal terminal output interleaved ---

    @Test
    fun `normal escape sequences before and after OSC 52`() {
        val b64 = encode("copy this")
        val input = "\u001b[2J\u001b]52;c;$b64\u0007\u001b[1;1H"
        val output = process(input)

        assertEquals("\u001b[2J\u001b[1;1H", String(output))
        assertEquals(1, clipboardResults.size)
        assertEquals("copy this", clipboardResults[0])
    }
}
