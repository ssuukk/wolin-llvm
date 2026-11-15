package pl.qus.wolin.sema

import pl.qus.wolin.ast.FunctionLit

/**
 * Resolves a function literal's parameter types from the expected type at the
 * literal's site.
 *
 * A literal has no type annotation of its own: the types of its parameters
 * come from the function type the literal is used as — the assignment
 * target's type, or the declared type of the variable the literal is
 * initialised with.
 */
object FunctionLiteralInfer {

    /**
     * The parameter types [lit] takes, from [expected].
     *
     * Throws [SemaException] when [expected] is not a function type, when it
     * has a different number of parameters than the literal, or when there is
     * no expected type at all.
     */
    fun infer(lit: FunctionLit, expected: WolinType?): List<WolinType> {
        if (expected == null) {
            throw SemaException(
                "cannot infer the parameter types of this function literal; " +
                    "give it an explicit type"
            )
        }
        if (expected.kind != TypeKind.FUNCTION) {
            throw SemaException(
                "a function literal needs a function type, not ${expected}"
            )
        }
        val params = expected.params
        if (params.size != lit.params.size) {
            throw SemaException(
                "function literal takes ${lit.params.size} parameters, " +
                    "but the expected type takes ${params.size}"
            )
        }
        return params
    }
}
