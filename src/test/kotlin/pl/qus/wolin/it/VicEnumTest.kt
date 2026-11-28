package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.SourceFile
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** The VIC-II enums in the stdlib: the values reach the registers. */
class VicEnumTest {

    @Test fun `the stdlib enums write the registers they name`() {
        val dir = Files.createTempDirectory("wolinc-vic-enum")
        val app = dir.resolve("app.wtk")
        app.writeText(
            """
            package app
            import c64.vic.Color
            import c64.vic.Control1
            import c64.vic.InterruptSource
            import c64.vic.SpriteMask
            import c64.vic.borderColor
            import c64.vic.backgroundColor0
            import c64.vic.spriteEnable
            import c64.vic.interruptEnable
            import c64.vic.controlRegister1
            var maskInterrupts: bool^CPU.I
            fun main() {
                maskInterrupts = true // keep the enabled raster IRQ from interrupting the writes
                borderColor = Color.ORANGE
                backgroundColor0 = Color.BLUE
                spriteEnable = SpriteMask.SPRITE0 | SpriteMask.SPRITE3
                interruptEnable = InterruptSource.RASTER
                controlRegister1 = Control1.DEN
                while (true) {}
            }
            """.trimIndent()
        )
        val inputs = listOf(
            Path.of("stdlib/c64/kernal.wtk"),
            Path.of("stdlib/c64/vic.wtk"),
            app,
        )
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-vic-enum-ir")
        try {
            val linked = compileToPrgFile(inputs, output, sdk, workDir)
            check(linked != null) { "the program did not compile" }
            // $d020-$d024 border and backgrounds, $d015 sprite enable,
            // $d01a interrupt enable, $d011 control register 1.
            val regs = ViceRunner().run(output, 0xd011, 0xd024)
            val at = { address: Int -> (regs[address - 0xd011].toInt() and 0xFF) }
            assertEquals(0x10, at(0xd011) and 0x7f, "controlRegister1 (Control1.DEN)")
            assertEquals(0x09, at(0xd015), "spriteEnable (SPRITE0|SPRITE3)")
            assertEquals(0x01, at(0xd01a) and 0x0f, "interruptEnable (InterruptSource.RASTER)")
            assertEquals(0x08, at(0xd020) and 0x0f, "borderColor (Color.ORANGE)")
            assertEquals(0x06, at(0xd021) and 0x0f, "backgroundColor0 (Color.BLUE)")
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
