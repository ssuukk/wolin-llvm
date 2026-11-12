package pl.qus.wolin.ir

/**
 * An SSA value.
 *
 * [name] is the full name including the `%` sigil, e.g. `%v0`. Function
 * parameters keep the names they were given (e.g. `%a`); every other value
 * gets a builder-allocated name.
 */
data class IrValue(val name: String, val type: IrType)
