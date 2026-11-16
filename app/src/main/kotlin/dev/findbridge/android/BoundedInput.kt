package dev.findbridge.android

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded native responses. Callers own the stream and close it with use. */
internal object BoundedInput {
    fun utf8(
        input: InputStream,
        limit: Int,
        declaredLength: Long = -1,
        isCurrent: () -> Boolean = { true },
    ): String = read(input, limit, declaredLength, isCurrent).toString(Charsets.UTF_8.name())

    fun bytes(input: InputStream, limit: Int, declaredLength: Long = -1): ByteArray =
        read(input, limit, declaredLength) { true }.toByteArray()

    private fun read(
        input: InputStream,
        limit: Int,
        declaredLength: Long,
        isCurrent: () -> Boolean,
    ): ByteArrayOutputStream {
        require(limit >= 0)
        check(declaredLength < 0 || declaredLength <= limit)
        // Do not reserve a server-controlled Content-Length before any bytes arrive.
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            check(isCurrent())
            val remaining = limit - output.size()
            // Read only one byte past the boundary, even for an unbounded chunked response.
            val count =
                input.read(buffer, 0, minOf(buffer.size.toLong(), remaining.toLong() + 1).toInt())
            if (count < 0) return output
            check(count <= remaining)
            output.write(buffer, 0, count)
        }
    }
}
