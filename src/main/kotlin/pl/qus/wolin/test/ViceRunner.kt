package pl.qus.wolin.test

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs a `.prg` on the real 6502 under VICE and reads its memory back.
 *
 * The emulator is started with the binary monitor enabled, the program is
 * autostarted, and the monitor's memory is read after the program has had
 * time to run.
 */
class ViceRunner(val vicePath: String = "/home/qus/.local/bin/x64sc") {

    /**
     * Runs [prg] under VICE and returns the bytes of memory [readStart]..[readEnd]
     * (inclusive). Byte `i` of the result is memory `readStart + i`.
     */
    fun run(
        prg: Path,
        readStart: Int,
        readEnd: Int,
        settleMillis: Long = RUN_SETTLE_MILLIS,
    ): ByteArray {
        // Each emulator gets its own monitor port: a fixed port makes two
        // concurrent runs fight over the listener, and the loser then reads
        // the *other* program's memory.
        val port = freePort()
        // Run headless: x64sc is a GTK front end, so it opens a window on the
        // tester's desktop unless it is given a virtual X server. `xvfb-run`
        // starts one for this emulator and tears it down afterwards.
        val command = ProcessBuilder(
            "xvfb-run",
            "-a",
            "-s", "-screen 0 640x480x24",
            vicePath,
            "-default",
            "-binarymonitor",
            "-binarymonitoraddress", "$HOST:$port",
            "-limitcycles", "0",
            "-warp",
            "-autostart", prg.toString(),
        )
        val process = command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            awaitMonitor(process, port)
            Thread.sleep(settleMillis)
            val monitor = ViceMonitor(HOST, port)
            val body = try {
                monitor.memGet(readStart, readEnd)
            } finally {
                monitor.close()
            }
            return readData(body, readStart, readEnd)
        } finally {
            terminate(process)
        }
    }

    /**
     * Picks a port the OS is not currently using. VICE binds it a moment
     * later, so there is a small race window; in practice the ephemeral port
     * range is wide enough for the handful of emulators a suite runs.
     */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Waits until the binary monitor accepts a connection, or the process dies,
     * or [MONITOR_TIMEOUT_MILLIS] passes.
     */
    private fun awaitMonitor(process: Process, port: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MONITOR_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw IOException("VICE exited with code ${process.exitValue()} before the monitor opened")
            }
            try {
                // The monitor serves one client at a time: this probe connection
                // is closed again before the real one is opened.
                Socket(HOST, port).use { }
                return
            } catch (e: IOException) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
        }
        throw IOException("VICE binary monitor did not open $HOST:$port within $MONITOR_TIMEOUT_MILLIS ms")
    }

    /**
     * The MEM_GET response body is the byte count (u16 LE) followed by the
     * bytes. Checks both against the requested range.
     */
    private fun readData(body: ByteArray, readStart: Int, readEnd: Int): ByteArray {
        if (body.size < 2) throw IOException("VICE binary monitor: short MEM_GET body (${body.size} bytes)")
        val length = (body[0].toInt() and 0xFF) or ((body[1].toInt() and 0xFF) shl 8)
        val data = body.copyOfRange(2, minOf(body.size, 2 + length))
        val expected = readEnd - readStart + 1
        if (length != expected || data.size != expected) {
            throw IOException(
                "VICE binary monitor: MEM_GET $readStart..$readEnd returned $length bytes, expected $expected",
            )
        }
        return data
    }

    /** Stops the emulator: no orphan emulators, whatever happened. */
    private fun terminate(process: Process) {
        // `xvfb-run` is the direct child; the emulator is its descendant.
        // Destroy the descendant first, so no window outlives the wrapper.
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(TERMINATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(TERMINATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val MONITOR_TIMEOUT_MILLIS = 40_000L
        const val RUN_SETTLE_MILLIS = 4_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val TERMINATE_TIMEOUT_MILLIS = 5_000L
    }
}
