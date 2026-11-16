package dev.findbridge.android

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test
    fun exactBoundaryKeepsUtf8AndUnknownLengthResponses() {
        val text = "デバイス🌏"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, BoundedInput.utf8(ByteArrayInputStream(bytes), bytes.size))
        assertArrayEquals(
            bytes,
            BoundedInput.bytes(ByteArrayInputStream(bytes), bytes.size, bytes.size.toLong()),
        )
        assertEquals("", BoundedInput.utf8(ByteArrayInputStream(byteArrayOf()), 0))
    }

    @Test
    fun oversizedDeclaredLengthDoesNotReadTheBody() {
        val input = CountingInput(ByteArray(100))
        assertThrows(IllegalStateException::class.java) { BoundedInput.utf8(input, 10, 11) }
        assertEquals(0, input.consumed)
    }

    @Test
    fun unknownLengthConsumesOnlyOneByteBeyondTheLimit() {
        val input = CountingInput(ByteArray(100_000))
        assertThrows(IllegalStateException::class.java) { BoundedInput.bytes(input, 10) }
        assertEquals(11, input.consumed)
    }

    @Test
    fun canceledSessionStopsBeforeAnotherBodyRead() {
        val input = CountingInput(ByteArray(100), chunkSize = 2)
        assertThrows(IllegalStateException::class.java) {
            BoundedInput.utf8(input, 100) { input.consumed < 4 }
        }
        assertEquals(4, input.consumed)
    }

    private class CountingInput(bytes: ByteArray, private val chunkSize: Int = Int.MAX_VALUE) :
        ByteArrayInputStream(bytes) {
        var consumed = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, chunkSize)).also {
                if (it > 0) consumed += it
            }
    }
}
