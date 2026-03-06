package sh.haven.feature.terminal

import android.util.Log
import java.util.Base64

private const val TAG = "Osc52Handler"

/**
 * Scans terminal output for OSC 52 clipboard-set sequences and strips them
 * from the byte stream so the terminal emulator doesn't render garbage.
 *
 * Format:
 *   ESC ] 52 ; <selection> ; <base64-data> BEL        (BEL = 0x07)
 *   ESC ] 52 ; <selection> ; <base64-data> ESC \      (ST  = 0x1B 0x5C)
 *
 * Only the "set" direction is supported (remote → Android clipboard).
 * Query direction (empty data) is ignored for security.
 *
 * On a complete sequence the base64 payload is decoded as UTF-8 and
 * delivered via [onClipboardSet]. Non-OSC-52 bytes pass through unchanged.
 *
 * Handles partial sequences across buffer boundaries. Invalid sequences
 * flush accumulated bytes to output. Payload capped at [MAX_PAYLOAD_BYTES].
 *
 * The handler owns a reusable output buffer to avoid per-call allocations.
 * Access the filtered output via [outputBuf] / [outputLen] after [process].
 */
class Osc52Handler(var onClipboardSet: (String) -> Unit = {}) {

    private enum class State {
        GROUND,     // Waiting for ESC
        ESC,        // Got ESC (0x1B)
        OSC_BRACKET,// Got ESC ]
        OSC_5,      // Got ESC ] 5
        OSC_52,     // Got ESC ] 52
        SEMI1,      // Got ESC ] 52 ; — collecting selection parameter
        SELECTION,  // Inside selection chars (until next ';')
        SEMI2,      // Got second ';' — about to collect data
        DATA,       // Collecting base64 data
        ST_ESC,     // Got ESC inside DATA — expecting '\'
    }

    companion object {
        /** Max base64 payload before aborting (prevents memory abuse). */
        const val MAX_PAYLOAD_BYTES = 1_048_576 // 1 MB

        /** seqBuf can hold at most ESC ] 5 2 ; <sel> ; = ~10 bytes. 64 is plenty. */
        private const val MAX_SEQ_OVERHEAD = 64
    }

    private var state = State.GROUND

    // Bytes buffered while inside a potential OSC 52 sequence, so we can
    // flush them if the sequence turns out to be something else.
    private val seqBuf = SimpleByteBuffer()
    private val dataBuf = SimpleByteBuffer()

    /** Reusable output buffer — grows as needed, never shrinks. */
    var outputBuf = ByteArray(4096)
        private set

    /** Number of valid bytes in [outputBuf] after the last [process] call. */
    var outputLen = 0
        private set

    /**
     * Process a chunk of terminal output. OSC 52 sequences are consumed;
     * everything else is written to [outputBuf]. Read [outputLen] bytes
     * from [outputBuf] starting at offset 0 for the filtered result.
     */
    fun process(data: ByteArray, offset: Int, length: Int) {
        // Ensure output buffer is large enough: input + any previously buffered seq bytes
        val needed = length + MAX_SEQ_OVERHEAD
        if (outputBuf.size < needed) {
            outputBuf = ByteArray(needed)
        }
        outputLen = 0

        val end = offset + length
        for (i in offset until end) {
            val b = data[i]
            val u = b.toInt() and 0xFF

            when (state) {
                State.GROUND -> {
                    if (u == 0x1B) {
                        state = State.ESC
                        seqBuf.reset()
                        seqBuf.write(u)
                    } else {
                        outputBuf[outputLen++] = b
                    }
                }

                State.ESC -> {
                    seqBuf.write(u)
                    if (u == ']'.code) {
                        state = State.OSC_BRACKET
                    } else {
                        flushSeqBuf()
                    }
                }

                State.OSC_BRACKET -> {
                    seqBuf.write(u)
                    if (u == '5'.code) {
                        state = State.OSC_5
                    } else {
                        flushSeqBuf()
                    }
                }

                State.OSC_5 -> {
                    seqBuf.write(u)
                    if (u == '2'.code) {
                        state = State.OSC_52
                    } else {
                        flushSeqBuf()
                    }
                }

                State.OSC_52 -> {
                    seqBuf.write(u)
                    if (u == ';'.code) {
                        state = State.SEMI1
                    } else {
                        flushSeqBuf()
                    }
                }

                State.SEMI1 -> {
                    seqBuf.write(u)
                    if (u == ';'.code) {
                        state = State.SEMI2
                    } else if (u in 0x20..0x7E) {
                        state = State.SELECTION
                    } else {
                        flushSeqBuf()
                    }
                }

                State.SELECTION -> {
                    seqBuf.write(u)
                    if (u == ';'.code) {
                        state = State.SEMI2
                    } else if (u in 0x20..0x7E) {
                        // More selection chars
                    } else {
                        flushSeqBuf()
                    }
                }

                State.SEMI2 -> {
                    dataBuf.reset()
                    state = State.DATA
                    processDataByte(b, u)
                }

                State.DATA -> {
                    processDataByte(b, u)
                }

                State.ST_ESC -> {
                    if (u == '\\'.code) {
                        completeSequence()
                        state = State.GROUND
                    } else {
                        flushAll()
                        if (u == 0x1B) {
                            state = State.ESC
                            seqBuf.reset()
                            seqBuf.write(u)
                        } else {
                            outputBuf[outputLen++] = b
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy API — process into a caller-provided output buffer.
     * @return number of bytes written to [output].
     */
    fun process(data: ByteArray, offset: Int, length: Int, output: ByteArray): Int {
        process(data, offset, length)
        System.arraycopy(outputBuf, 0, output, 0, outputLen)
        return outputLen
    }

    private fun processDataByte(b: Byte, u: Int) {
        when {
            u == 0x07 -> {
                completeSequence()
                state = State.GROUND
            }
            u == 0x1B -> {
                state = State.ST_ESC
            }
            dataBuf.size() >= MAX_PAYLOAD_BYTES -> {
                Log.w(TAG, "OSC 52 payload exceeded $MAX_PAYLOAD_BYTES bytes, aborting")
                flushAll()
                outputBuf[outputLen++] = b
            }
            else -> {
                dataBuf.write(u)
            }
        }
    }

    private fun completeSequence() {
        val base64Bytes = dataBuf.toByteArray()
        dataBuf.reset()
        seqBuf.reset()

        if (base64Bytes.isEmpty()) return // query request — ignore

        try {
            val decoded = Base64.getMimeDecoder().decode(base64Bytes)
            val text = String(decoded, Charsets.UTF_8)
            onClipboardSet(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode OSC 52 base64 payload", e)
        }
    }

    private fun flushSeqBuf() {
        seqBuf.copyInto(outputBuf, outputLen)
        outputLen += seqBuf.size()
        seqBuf.reset()
        state = State.GROUND
    }

    private fun flushAll() {
        seqBuf.copyInto(outputBuf, outputLen)
        outputLen += seqBuf.size()
        seqBuf.reset()

        dataBuf.copyInto(outputBuf, outputLen)
        outputLen += dataBuf.size()
        dataBuf.reset()

        state = State.GROUND
    }
}

/**
 * Minimal growable byte buffer. Not thread-safe.
 */
private class SimpleByteBuffer {
    private var buf = ByteArray(256)
    private var count = 0

    fun write(b: Int) {
        ensureCapacity(count + 1)
        buf[count++] = b.toByte()
    }

    fun size(): Int = count

    fun reset() {
        count = 0
    }

    fun toByteArray(): ByteArray = buf.copyOfRange(0, count)

    fun copyInto(dest: ByteArray, destOffset: Int) {
        System.arraycopy(buf, 0, dest, destOffset, count)
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buf.size) {
            val newSize = maxOf(buf.size * 2, minCapacity)
            buf = buf.copyOf(newSize)
        }
    }
}
