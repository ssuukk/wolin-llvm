package pl.qus.wolin.driver

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The symbol map that ties a Wolin external function to the symbol a
 * linked archive defines.
 *
 * Each line is `<wolin symbol> = <rust symbol>`: the Wolin symbol is the
 * one the compiler emits, the Rust symbol is the one the archive exports.
 */
class RustMapTest {
    private fun parse(text: String): RustMap {
        val file = Files.createTempDirectory("wolinc-map").resolve("map.txt")
        file.writeText(text)
        return RustMap.parse(file)
    }

    @Test fun `a single pair is parsed`() {
        val map = parse("t.add = wol_add\n")
        assertEquals(listOf("t.add" to "wol_add"), map.entries)
    }

    @Test fun `several pairs are parsed in order`() {
        val map = parse("t.add = wol_add\nt.sub = wol_sub\n")
        assertEquals(listOf("t.add" to "wol_add", "t.sub" to "wol_sub"), map.entries)
    }

    @Test fun `comments and blank lines are ignored`() {
        val map = parse("# a comment\n\n  t.add = wol_add  \n\n# another\n")
        assertEquals(listOf("t.add" to "wol_add"), map.entries)
    }

    @Test fun `an empty file has no entries`() {
        assertEquals(emptyList(), parse("").entries)
    }

    @Test fun `a line without a separator is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { parse("t.add wol_add\n") }
        assertTrue(e.message!!.contains("t.add wol_add"), e.message)
    }

    @Test fun `a line with an empty side is rejected`() {
        assertFailsWith<IllegalArgumentException> { parse("= wol_add\n") }
        assertFailsWith<IllegalArgumentException> { parse("t.add =\n") }
    }

    @Test fun `a line with two separators is rejected`() {
        assertFailsWith<IllegalArgumentException> { parse("t.add = a = b\n") }
    }

    @Test fun `a duplicate wolin symbol is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { parse("t.add = a\nt.add = b\n") }
        assertTrue(e.message!!.contains("t.add"), e.message)
    }

    @Test fun `the defsym argument names the alias and the target`() {
        val map = parse("t.add = wol_add\n")
        assertEquals(listOf("--defsym=t.add=wol_add"), map.defsymArguments())
    }
}
