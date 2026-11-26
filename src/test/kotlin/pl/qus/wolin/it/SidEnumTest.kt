package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** The SID enums in the stdlib: the values reach the chip. */
class SidEnumTest {

    @Test fun `the stdlib enums write the SID registers they name`() {
        val dir = Files.createTempDirectory("wolinc-sid-enum")
        val app = dir.resolve("app.wtk")
        app.writeText(
            """
            package app
            import c64.sid.FilterMode
            import c64.sid.FilterRouting
            import c64.sid.VoiceControl
            import c64.sid.Waveform
            import c64.sid.filterModeVolume
            import c64.sid.filterResonanceRouting
            import c64.sid.voice1Control
            import c64.sid.voice1FrequencyHigh
            import c64.sid.voice1FrequencyLow
            fun main() {
                voice1FrequencyLow = 0x00
                voice1FrequencyHigh = 0x10
                voice1Control = Waveform.PULSE | VoiceControl.GATE
                filterResonanceRouting = 0x08 | FilterRouting.VOICE1
                filterModeVolume = FilterMode.LOWPASS
                while (true) {}
            }
            """.trimIndent()
        )
        val inputs = listOf(
            Path.of("stdlib/c64/kernal.wtk"),
            Path.of("stdlib/c64/sid.wtk"),
            app,
        )
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-sid-enum-ir")
        try {
            val linked = compileToPrgFile(inputs, output, sdk, workDir)
            check(linked != null) { "the program did not compile" }
            // $d400-$d418 are the SID's write registers; a read returns the
            // last value written to the register.
            val regs = ViceRunner().run(output, 0xd400, 0xd418)
            val at = { offset: Int -> (regs[offset].toInt() and 0xFF) }
            assertEquals(0x00, at(0x00), "voice1FrequencyLow")
            assertEquals(0x10, at(0x01), "voice1FrequencyHigh")
            assertEquals(0x41, at(0x04), "voice1Control (PULSE|GATE)")
            assertEquals(0x09, at(0x17), "filterResonanceRouting (0x08|VOICE1)")
            assertEquals(0x10, at(0x18), "filterModeVolume (LOWPASS)")
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
