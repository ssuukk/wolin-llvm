package pl.qus.wolin.test

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile

/**
 * The emulator must not outlive the JVM that started it.
 *
 * A test run that is interrupted — the Gradle build is cancelled, the
 * process is killed — never reaches the normal cleanup. The emulator is
 * then reparented to init and spins at full CPU for the rest of the
 * machine's uptime.
 *
 * The shutdown hook removes it: it runs on the normal exit and on the
 * kill, as long as the JVM can still run its hooks.
 */
class ViceRunnerCleanupTest {

    @Test fun `the cleanup removes the emulator`() {
        val handle = ViceRunner().start(prg())
        assertTrue(handle.isAlive, "the emulator must be running before the cleanup")
        handle.cleanupNow()
        assertFalse(handle.isAlive, "the emulator must be gone after the cleanup")
    }

    @Test fun `the cleanup kills the whole process tree`() {
        val handle = ViceRunner().start(prg())
        // The wrapper starts the emulator and the X server only after its
        // own start-up wait, so the tree is not there yet.
        val descendants = awaitDescendants(handle)
        assertTrue(descendants > 0, "the emulator runs under a wrapper, so it has descendants")
        handle.cleanupNow()
        assertFalse(
            handle.process.descendants().anyMatch { it.isAlive },
            "the wrapper's descendants must be gone too",
        )
    }

    /** Waits until the emulator's process tree has formed. */
    private fun awaitDescendants(handle: ViceRunner.Emulator): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val count = handle.process.descendants().count()
            if (count > 0) return count
            Thread.sleep(200)
        }
        return 0
    }

    @Test fun `the cleanup is safe to run twice`() {
        val handle = ViceRunner().start(prg())
        handle.cleanupNow()
        handle.cleanupNow()
        assertFalse(handle.isAlive)
    }

    @Test fun `the emulator tree shares one process group`() {
        val handle = ViceRunner().start(prg())
        awaitDescendants(handle)
        // The group is what makes the tree killable in one signal: without
        // it the wrapper, the X server and the emulator are three separate
        // targets, and the X server survives the other two.
        val descendants = handle.process.descendants().toList()
        assertTrue(descendants.isNotEmpty(), "the emulator must have descendants")
        for (descendant in descendants) {
            assertEquals(
                handle.group,
                processGroupOf(descendant.pid()),
                "process ${descendant.pid()} is not in the emulator's group",
            )
        }
    }

    /**
     * The process group of [pid], read from `/proc`. The field is the
     * fifth of the stat line's parenthesised part.
     */
    private fun processGroupOf(pid: Long): Long {
        val stat = Files.readString(Path.of("/proc", pid.toString(), "stat"))
        val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
        // After the state, the fields are ppid, pgrp, session.
        return fields[2].toLong()
    }

    /**
     * A `.prg` that runs forever, so the emulator stays up long enough to
     * be inspected.
     */
    private fun prg(): Path {
        val dir = Files.createTempDirectory("wolinc-cleanup")
        val src = dir.resolve("t.wtk")
        src.writeText("fun main() { while (true) {} }\n")
        val out = dir.resolve("t.prg")
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val work = Files.createTempDirectory("wolinc-cleanup-ir")
        val linked = compileToPrgFile(listOf(src), out.toAbsolutePath(), sdk, work)
        checkNotNull(linked) { "the program must compile" }
        return out.toAbsolutePath()
    }
}
