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
     * A running emulator, and the cleanup that removes it.
     *
     * The emulator is a `xvfb-run` wrapper with the emulator and the X
     * server under it. Removing it means removing the whole tree: the
     * wrapper alone leaves the other two running.
     */
    class Emulator(val process: Process, val port: Int, val group: Long) {
        /** Whether the emulator is still running. */
        val isAlive: Boolean get() = process.isAlive

        /**
         * Removes the emulator when the JVM exits normally.
         *
         * This is the polite path: the emulator goes away as soon as the
         * test is done. It is not the only one — a JVM killed outright
         * runs no hooks, and the [ViceRunner] watchdog covers that case.
         */
        fun killOnExit() {
            Runtime.getRuntime().addShutdownHook(Thread { cleanupNow() })
        }

        /** Removes the emulator and everything under it. Safe to call twice. */
        fun cleanupNow() {
            killTree(process, group)
        }
    }

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
        val emulator = start(prg)
        try {
            awaitMonitor(emulator.process, emulator.port)
            Thread.sleep(settleMillis)
            val monitor = ViceMonitor(HOST, emulator.port)
            val body = try {
                monitor.memGet(readStart, readEnd)
            } finally {
                monitor.close()
            }
            return readData(body, readStart, readEnd)
        } finally {
            emulator.cleanupNow()
        }
    }

    /**
     * Starts the emulator for [prg] and returns it.
     *
     * The emulator is registered for cleanup on JVM exit, so an
     * interrupted test run leaves nothing behind.
     */
    fun start(prg: Path): Emulator {
        // Each emulator gets its own monitor port: a fixed port makes two
        // concurrent runs fight over the listener, and the loser then reads
        // the *other* program's memory.
        val port = freePort()
        // Run headless: x64sc is a GTK front end, so it opens a window on the
        // tester's desktop unless it is given a virtual X server. `xvfb-run`
        // starts one for this emulator and tears it down afterwards.
        //
        // `setsid` puts the wrapper in a process group of its own, so the
        // whole tree — the wrapper, the X server and the emulator — shares
        // one group id and one signal removes all of it. Without it the
        // tree shares the JVM's group, and a group kill would take the JVM
        // with it.
        val command = ProcessBuilder(
            "setsid",
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
        // The wrapper is the group leader, so its pid is the group id.
        val group = process.pid()
        startWatchdog(group)
        val emulator = Emulator(process, port, group)
        emulator.killOnExit()
        return emulator
    }

    /**
     * Starts the watchdog that removes the emulator's process group when
     * this JVM is gone.
     *
     * A shutdown hook is not enough: `SIGKILL` — which is how a cancelled
     * Gradle build ends the test JVM — runs no hooks at all, and the
     * emulator would be reparented to init and spin at full CPU for the
     * rest of the machine's uptime. The watchdog is a separate process, so
     * it outlives the JVM and does the cleanup the hook would have done.
     *
     * The watchdog waits for either the JVM or the emulator to go: it
     * must not outlive the emulator it watches, or a suite that runs many
     * emulators would pile up watchers.
     */
    private fun startWatchdog(group: Long) {
        val jvm = ProcessHandle.current().pid()
        val script = "while kill -0 $jvm 2>/dev/null && kill -0 $group 2>/dev/null; do " +
            "sleep 1; done; kill -9 -$group 2>/dev/null"
        ProcessBuilder("sh", "-c", script)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
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

    private companion object {
        const val HOST = "127.0.0.1"
        const val MONITOR_TIMEOUT_MILLIS = 40_000L
        const val RUN_SETTLE_MILLIS = 4_000L
        const val POLL_INTERVAL_MILLIS = 250L
        const val TERMINATE_TIMEOUT_MILLIS = 5_000L

        /**
         * Removes [process] and everything under it.
         *
         * The whole tree shares one process group, so a signal to the
         * group removes the wrapper, the X server and the emulator at
         * once. The descendants are asked to stop first, so no window
         * outlives the wrapper.
         */
        fun killTree(process: Process, group: Long) {
            process.descendants().forEach { it.destroy() }
            process.destroy()
            if (!process.waitFor(TERMINATE_TIMEOUT_MILLIS, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(TERMINATE_TIMEOUT_MILLIS, TimeUnit.SECONDS)
            }
            // The group outlives the wrapper: a descendant that was
            // reparented to init is still in it, and only the group
            // signal reaches it.
            killGroup(group)
        }

        /** Removes every process in [group]. */
        fun killGroup(group: Long) {
            val killed = ProcessBuilder("kill", "-9", "-$group")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            killed.waitFor(TERMINATE_TIMEOUT_MILLIS, TimeUnit.SECONDS)
        }
    }
}
