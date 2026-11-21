package pl.qus.wolin.sema

class SemaException(message: String, val line: Int? = null) : Exception(message)
