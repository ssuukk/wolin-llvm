package pl.qus.wolin.driver

class Diagnostics {
    private val _messages = mutableListOf<String>()

    val messages: List<String> get() = _messages
    val hasErrors: Boolean get() = _messages.isNotEmpty()

    fun error(msg: String, line: Int, col: Int) {
        _messages += "error: $msg (line $line, column $col)"
    }
}
