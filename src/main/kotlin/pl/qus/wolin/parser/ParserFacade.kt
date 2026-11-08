package pl.qus.wolin.parser

import org.antlr.v4.runtime.*
import pl.qus.wolin.driver.Diagnostics

class ParserFacade(private val diag: Diagnostics) {
    fun parse(source: String, fileName: String): KotlinParser.KotlinFileContext? {
        val lexer = KotlinLexer(CharStreams.fromString(source, fileName))
        lexer.removeErrorListeners()
        lexer.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>, offendingSymbol: Any?,
                line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?
            ) { diag.error(msg, line, charPositionInLine) }
        })

        val parser = KotlinParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>, offendingSymbol: Any?,
                line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?
            ) { diag.error(msg, line, charPositionInLine) }
        })

        val tree = parser.kotlinFile()
        return if (diag.hasErrors) null else tree
    }
}
