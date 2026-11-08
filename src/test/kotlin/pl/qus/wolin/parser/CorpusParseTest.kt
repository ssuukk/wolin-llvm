package pl.qus.wolin.parser

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

class CorpusParseTest {
    @ParameterizedTest
    @ValueSource(strings = ["strings", "misc", "objects", "raster_irq"])
    fun oldCorpusParsesWithoutErrors(name: String) {
        val src = javaClass.getResource("/corpus/$name.ktk")!!.readText()
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "$name.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors in $name.ktk: ${diag.messages}")
    }
}
