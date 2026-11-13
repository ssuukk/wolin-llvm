package pl.qus.wolin.driver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ManglerTest {
    @Test fun `symbols are mangled by package file and name`() {
        assertEquals("game.demo.main", Mangler.mangle("game", "demo", "main"))
    }
}
