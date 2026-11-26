package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** The CIA enums in the stdlib: the values reach the chips. */
class CiaEnumTest {

    @Test fun `the stdlib enums write the CIA registers they name`() {
        val dir = Files.createTempDirectory("wolinc-cia-enum")
        val app = dir.resolve("app.wtk")
        app.writeText(
            """
            package app
            import c64.cia.CiaControl
            import c64.cia.InterruptMask
            import c64.cia.InterruptSource
            import c64.cia.TimerBClock
            import c64.cia.VicBank
            import c64.cia.cia1ControlA
            import c64.cia.cia1ControlB
            import c64.cia.cia1InterruptControl
            import c64.cia.cia2PortA
            fun main() {
                cia1ControlA = CiaControl.START
                cia1ControlB = TimerBClock.TIMER_A
                cia1InterruptControl = InterruptMask.SET | InterruptSource.TIMER_B
                cia2PortA = VicBank.BANK1
                while (true) {}
            }
            """.trimIndent()
        )
        val inputs = listOf(
            Path.of("stdlib/c64/kernal.wtk"),
            Path.of("stdlib/c64/cia.wtk"),
            app,
        )
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-cia-enum-ir")
        try {
            val linked = compileToPrgFile(inputs, output, sdk, workDir)
            check(linked != null) { "the program did not compile" }
            // CIA 1 is at $dc00 and CIA 2 at $dd00.
            val cia1 = ViceRunner().run(output, 0xdc00, 0xdc0f)
            val at = { offset: Int -> (cia1[offset].toInt() and 0xFF) }
            // The control registers read back the running state: the start
            // bit shows the timer running, the other bits are as written.
            assertEquals(0x01, at(0x0e), "cia1ControlA (CiaControl.START)")
            assertEquals(0x40, at(0x0f), "cia1ControlB (TimerBClock.TIMER_A)")

            val cia2 = ViceRunner().run(output, 0xdd00, 0xdd0f)
            // Port A bits 6-7 are the serial bus clock and data inputs, so
            // they read as 1; the bank select is in bits 0-1.
            assertEquals(0x02, (cia2[0].toInt() and 0x03), "cia2PortA (VicBank.BANK1)")
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
