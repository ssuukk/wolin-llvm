package pl.qus.wolin.test

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * A client of the VICE binary monitor (VICE 3.10).
 *
 * The monitor server accepts one client at a time, so a monitor owns its
 * connection until [close].
 *
 * A request is an 11-byte header followed by the body:
 *
 * ```
 * STX (0x02) | api (0x02) | body length (u32 LE, body only)
 *            | request id (u32 LE) | command type (u8)
 * ```
 *
 * A response is a 12-byte header followed by the body:
 *
 * ```
 * STX (0x02) | api (0x02) | body length (u32 LE, body only)
 *            | response type (u8) | error code (u8) | request id (u32 LE)
 * ```
 */
class ViceMonitor(host: String = "127.0.0.1", port: Int = 6502) : Closeable {

    private val socket = Socket(host, port)
    private val input = DataInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private var closed = false

    /**
     * Reads the memory range [start]..[end] (inclusive) from main RAM.
     *
     * Returns the response body: the byte count (u16 LE) followed by the
     * bytes themselves.
     *
     * The monitor pushes unsolicited event frames at any time; those carry
     * the request id `0xffffffff` and are skipped.
     */
    fun memGet(start: Int, end: Int): ByteArray {
        sendRequest(COMMAND_MEM_GET, memGetBody(start, end))
        while (true) {
            val frame = readFrame()
            if (frame.requestId == UNSOLICITED_REQUEST_ID) continue
            if (frame.requestId != REQUEST_ID) continue
            if (frame.errorCode != 0) {
                throw IllegalStateException(
                    "VICE binary monitor: MEM_GET $start..$end failed, error code " +
                        "0x${frame.errorCode.toString(16).padStart(2, '0')} (${frame.errorCode})",
                )
            }
            return frame.body
        }
    }

    /** Closes the connection to the monitor. */
    override fun close() {
        if (closed) return
        closed = true
        socket.close()
    }

    private fun sendRequest(commandType: Int, body: ByteArray) {
        val request = ByteArrayOutputStream(REQUEST_HEADER_SIZE + body.size)
        request.write(STX)
        request.write(API_VERSION)
        request.writeU32LE(body.size.toLong())
        request.writeU32LE(REQUEST_ID)
        request.write(commandType)
        request.write(body)
        output.write(request.toByteArray())
        output.flush()
    }

    /** The MEM_GET body: side effects, start, end (inclusive), memory space, bank. */
    private fun memGetBody(start: Int, end: Int): ByteArray {
        val body = ByteArrayOutputStream(MEM_GET_BODY_SIZE)
        body.write(0) // side effects: none
        body.writeU16LE(start)
        body.writeU16LE(end)
        body.write(0) // memory space: main RAM
        body.writeU16LE(0) // bank
        return body.toByteArray()
    }

    private fun readFrame(): Frame {
        val header = ByteArray(RESPONSE_HEADER_SIZE)
        input.readFully(header)
        val bodyLength = header.u32LE(2)
        if (bodyLength > MAX_BODY) {
            throw IOException("VICE binary monitor: implausible body length $bodyLength")
        }
        val body = ByteArray(bodyLength.toInt())
        input.readFully(body)
        return Frame(
            errorCode = header[7].toInt() and 0xFF,
            requestId = header.u32LE(8),
            body = body,
        )
    }

    /** One response frame: its error code, its request id and its body. */
    private class Frame(
        val errorCode: Int,
        val requestId: Long,
        val body: ByteArray,
    )

    private companion object {
        const val STX = 0x02
        const val API_VERSION = 0x02
        const val COMMAND_MEM_GET = 0x01
        const val REQUEST_ID = 1L
        const val UNSOLICITED_REQUEST_ID = 0xFFFFFFFFL
        const val REQUEST_HEADER_SIZE = 11
        const val RESPONSE_HEADER_SIZE = 12
        const val MEM_GET_BODY_SIZE = 8
        const val MAX_BODY = 1 shl 20
    }
}

/** Writes [value] as four little-endian bytes. */
private fun ByteArrayOutputStream.writeU32LE(value: Long) {
    write((value and 0xFF).toInt())
    write(((value shr 8) and 0xFF).toInt())
    write(((value shr 16) and 0xFF).toInt())
    write(((value shr 24) and 0xFF).toInt())
}

/** Writes [value] as two little-endian bytes. */
private fun ByteArrayOutputStream.writeU16LE(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

/** Reads the little-endian u32 at [offset]. */
private fun ByteArray.u32LE(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
