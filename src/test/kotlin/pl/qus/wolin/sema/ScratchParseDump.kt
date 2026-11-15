package pl.qus.wolin.sema

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import pl.qus.wolin.parser.KotlinLexer
import pl.qus.wolin.parser.KotlinParser
import java.io.File
import org.junit.jupiter.api.Test

class ScratchParseDump {
    @Test
    fun dump() {
        val src = """fun f() { var b: byte = 1; var x = b.toWord() }"""
        val lexer = KotlinLexer(CharStreams.fromString(src, "t.ktk"))
        val parser = KotlinParser(CommonTokenStream(lexer))
        val tree = parser.kotlinFile()
        File("/tmp/parse-dump.txt").writeText(tree.toStringTree(parser))
    }
}
