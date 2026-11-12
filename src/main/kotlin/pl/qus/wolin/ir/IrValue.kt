package pl.qus.wolin.ir

/**
 * An SSA value.
 *
 * [name] is the full name including the `%` sigil, e.g. `%v0`. Function
 * parameters keep the names they were given (e.g. `%a`); every other value
 * gets a builder-allocated name. A literal instead carries the constant's
 * own spelling — `5`, `true`, `null` — so it is not materialised into an
 * SSA value and is rendered inline in the instruction that uses it.
 */
data class IrValue(val name: String, val type: IrType)
