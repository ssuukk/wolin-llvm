package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.sema.AstBuilder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Labels and labelled jumps build into the AST from the grammar's existing
 * tokens: the `LabelDefinition` token for the `name@` statement, the single
 * `*_AT` tokens for `break@name` / `continue@name` / `return@name`, and the
 * maximal-munch `LabelDefinition` + literal path for `return@<address>`.
 *
 * A label name may be any identifier: the lexer's `LabelDefinition` and
 * `*_AT` rules embed the Identifier pattern, so keyword spellings lex
 * cleanly on both sides.
 */
class LabelGrammarTest {
    private fun build(src: String): Ast.Program {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(src, "t.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors: ${diag.messages}")
        return AstBuilder().build(tree!!)
    }

    @Test fun `a label statement builds a LabelStmt`() {
        val program = build("fun f() { x@ }")
        assertEquals(listOf(Ast.LabelStmt("x")), program.functions[0].body)
    }

    @Test fun `a labelled break builds a BreakStmt with the label`() {
        val program = build("fun f() { break@x }")
        assertEquals(listOf(Ast.BreakStmt("x")), program.functions[0].body)
    }

    @Test fun `a labelled continue builds a ContinueStmt with the label`() {
        val program = build("fun f() { continue@x }")
        assertEquals(listOf(Ast.ContinueStmt("x")), program.functions[0].body)
    }

    @Test fun `a labelled return builds a ReturnAtStmt with the label target`() {
        val program = build("fun f() { return@x }")
        assertEquals(
            listOf(Ast.ReturnAtStmt(Ast.ReturnAtLabel("x"))),
            program.functions[0].body,
        )
        assertTrue(
            program.functions[0].returnAt.isEmpty(),
            "the address-site list must stay empty for a label target",
        )
    }

    @Test fun `a keyword-spelled label name builds`() {
        val program = build("fun f() { var@; break@var }")
        assertEquals(listOf(Ast.LabelStmt("var"), Ast.BreakStmt("var")), program.functions[0].body)
    }

    @Test fun `the address form still builds an address target`() {
        val program = build("fun f() { return@0xea31 }")
        assertEquals(
            listOf(Ast.ReturnAtStmt(Ast.ReturnAtAddress(0xea31))),
            program.functions[0].body,
        )
    }
}
