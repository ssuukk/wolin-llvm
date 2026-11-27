package pl.qus.wolin.sema

import pl.qus.wolin.ast.*
import java.util.IdentityHashMap
import java.util.LinkedHashMap

/** Names a struct declaration may not reuse. */
private val BUILTIN_TYPE_NAMES = setOf(
    "ubyte", "byte", "uword", "word", "bool", "unit", "float", "string",
)

/**
 * Walks the typed AST and enforces the type rules.
 *
 * The checker throws [SemaException] on the **first** error — it does not
 * accumulate.
 *
 * The returned [Program] carries a `resolved` map that binds every variable
 * reference to the declaration it resolved to. The map uses reference identity
 * for its keys, because two references to two different declarations of the
 * same name and type must stay distinguishable.
 */
class TypeChecker {
    private var scope: Scope = Scope()
    private var resolved: MutableMap<VarRef, Symbol> = IdentityHashMap()
    private var currentReturnType: WolinType? = null
    private var currentFunctionName: String = ""
    /** Labels defined in the function being checked; null outside one. */
    private var currentLabels: Set<String>? = null
    private val importResolver = ImportResolver()
    /** The imports of the file being checked. */
    private var fileImports: List<ImportDecl> = emptyList()
    /** The whole-program view the imports resolve against. */
    private var program: Ast.Program? = null

    /** The structs visible in the file being checked. */
    private var structTypes: MutableMap<String, StructDecl> = mutableMapOf()

    /** The traits visible in the file being checked. */
    private var traitTypes: MutableMap<String, TraitDecl> = mutableMapOf()

    /** The named enums visible in the file being checked. */
    private var enumTypes: MutableMap<String, EnumDecl> = mutableMapOf()

    /** The receiver of the impl method being checked, or null outside one. */
    private var currentThis: StructDecl? = null

    /** Every method binding in the program, keyed by struct. */
    private val methodsByStruct = java.util.IdentityHashMap<StructDecl, LinkedHashMap<String, MethodBinding>>()
    private val methodBindings = mutableListOf<MethodBinding>()
    /** One vtable per trait impl, filled while impls are prepared. */
    private val vtableInfos = mutableListOf<Ast.VTableInfo>()
    /** Traits already implemented by each struct, by declaration identity. */
    private val implementedTraits =
        java.util.IdentityHashMap<StructDecl, MutableSet<TraitDecl>>()

    /** Structs whose layout has already been computed. */
    private val preparedStructs = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<StructDecl, Boolean>(),
    )

    fun check(file: Ast.Program): Ast.Program {
        resolved = IdentityHashMap()
        program = file
        preparedStructs.clear()
        methodsByStruct.clear()
        methodBindings.clear()
        vtableInfos.clear()
        implementedTraits.clear()
        // The whole-program view: each file is checked in its own scope, so
        // a name from another package is visible only through an import.
        val scopes = file.files.ifEmpty { listOf(singleFileScope(file)) }
        scopes.flatMap { it.structs }.forEach { prepareStruct(it) }
        // Traits and impls must be known before any method call is checked,
        // because a call resolves against the method table, not the AST walk.
        scopes.forEach { prepareTraitsAndImpls(it, scopes, file) }
        scopes.forEach { fileScope ->
            checkFile(fileScope, file)
        }
        return file.copy(
            resolved = resolved,
            methods = methodBindings.toList(),
            vtables = vtableInfos.toList(),
        )
    }

    /** The scope of a program built from a single file. */
    private fun singleFileScope(file: Ast.Program): FileScope = FileScope(
        fileName = "",
        packageName = file.packageName,
        globals = file.globals,
        functions = file.functions,
        structs = file.structs,
        enums = file.enums,
        traits = file.traits,
        impls = file.impls,
        imports = file.imports,
        mangledPrefix = "",
    )

    /**
     * The structs a file can name: its own, the same-package ones, and the
     * imported ones. A duplicate name in one package is a compile error.
     */
    private fun visibleStructs(fileScope: FileScope, program: Ast.Program): MutableMap<String, StructDecl> {
        val visible = LinkedHashMap<String, StructDecl>()
        fun add(decl: StructDecl) {
            val prior = visible[decl.name]
            if (prior != null && prior !== decl) {
                throw SemaException("Duplicate struct ${decl.name}")
            }
            visible[decl.name] = decl
        }
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other -> other.structs.forEach { add(it) } }
        fileScope.structs.forEach { add(it) }
        fileScope.imports.forEach { import ->
            val path = import.path
            val imported = if (path.endsWith(".*")) {
                val packageName = path.removeSuffix(".*")
                program.files.filter { it.packageName == packageName }.flatMap { it.structs }
            } else {
                val separator = path.lastIndexOf('.')
                if (separator < 0) emptyList() else {
                    val packageName = path.substring(0, separator)
                    val name = path.substring(separator + 1)
                    program.files.filter { it.packageName == packageName }
                        .flatMap { it.structs }
                        .filter { it.name == name }
                }
            }
            imported.forEach { visible.putIfAbsent(it.name, it) }
        }
        return visible
    }

    /**
     * The traits a file can name: its own, the same-package ones, and the
     * imported ones. A duplicate name in one package is a compile error.
     */
    private fun visibleTraits(fileScope: FileScope, program: Ast.Program): MutableMap<String, TraitDecl> {
        val visible = LinkedHashMap<String, TraitDecl>()
        fun add(decl: TraitDecl) {
            val prior = visible[decl.name]
            if (prior != null && prior !== decl) {
                throw SemaException("Duplicate trait ${decl.name}")
            }
            visible[decl.name] = decl
        }
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other -> other.traits.forEach { add(it) } }
        fileScope.traits.forEach { add(it) }
        fileScope.imports.forEach { import ->
            val path = import.path
            val imported = if (path.endsWith(".*")) {
                val packageName = path.removeSuffix(".*")
                program.files.filter { it.packageName == packageName }.flatMap { it.traits }
            } else {
                val separator = path.lastIndexOf('.')
                if (separator < 0) emptyList() else {
                    val packageName = path.substring(0, separator)
                    val name = path.substring(separator + 1)
                    program.files.filter { it.packageName == packageName }
                        .flatMap { it.traits }
                        .filter { it.name == name }
                }
            }
            imported.forEach { visible.putIfAbsent(it.name, it) }
        }
        return visible
    }

    /**
     * The named enums a file can name: its own, the same-package ones, and
     * the imported ones. Anonymous enums have no type name and are skipped.
     */
    private fun visibleEnums(fileScope: FileScope, program: Ast.Program): MutableMap<String, EnumDecl> {
        val visible = LinkedHashMap<String, EnumDecl>()
        fun add(decl: EnumDecl) {
            val name = decl.name ?: return
            val prior = visible[name]
            if (prior != null && prior !== decl) {
                throw SemaException("Duplicate enum $name", decl.line)
            }
            visible[name] = decl
        }
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other -> other.enums.forEach { add(it) } }
        fileScope.enums.forEach { add(it) }
        fileScope.imports.forEach { import ->
            val path = import.path
            val imported = if (path.endsWith(".*")) {
                val packageName = path.removeSuffix(".*")
                program.files.filter { it.packageName == packageName }.flatMap { it.enums }
            } else {
                val separator = path.lastIndexOf('.')
                if (separator < 0) emptyList() else {
                    val packageName = path.substring(0, separator)
                    val name = path.substring(separator + 1)
                    program.files.filter { it.packageName == packageName }
                        .flatMap { it.enums }
                        .filter { it.name == name }
                }
            }
            imported.forEach { if (it.name != null) visible.putIfAbsent(it.name, it) }
        }
        return visible
    }

    /**
     * Computes a struct's packed layout once. Fields are in declaration
     * order, with no padding: the 6502 has no alignment requirement.
     */
    private fun prepareStruct(decl: StructDecl) {
        if (!preparedStructs.add(decl)) return
        if (decl.name in BUILTIN_TYPE_NAMES || decl.name == "CPU") {
            throw SemaException("Struct name ${decl.name} is reserved")
        }
        if (decl.fields.isEmpty()) {
            throw SemaException("Struct ${decl.name} must have at least one field")
        }
        decl.fieldOffsets.clear()
        val seen = mutableSetOf<String>()
        var offset = 0
        for (field in decl.fields) {
            if (!seen.add(field.name)) {
                throw SemaException("Duplicate field ${field.name}", field.line)
            }
            if (field.location != null) {
                throw SemaException("Struct field ${field.name} cannot have a fixed address", field.line)
            }
            if (field.isVal) {
                throw SemaException("Struct field ${field.name} must be var", field.line)
            }
            if (field.init != null) {
                throw SemaException("Struct field initialisers are not supported", field.line)
            }
            val type = field.declaredType
                ?: throw SemaException("Struct field ${field.name} must declare a type", field.line)
            if (!isStructFieldType(type)) {
                throw SemaException("Struct field ${field.name} is not a scalar type: $type", field.line)
            }
            field.type = type
            decl.fieldOffsets[field.name] = offset
            offset += sizeOf(type)
        }
        if (offset > 65536) {
            throw SemaException("Struct ${decl.name} does not fit in the 64 KiB address space")
        }
        decl.size = offset
    }

    private fun isStructFieldType(type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE, TypeKind.BYTE, TypeKind.UWORD, TypeKind.WORD,
        TypeKind.BOOL, TypeKind.FLOAT, TypeKind.STRING -> true
        else -> false
    }

    /**
     * Validates the traits and impls of one file and fills the program-wide
     * method table. Runs before any body is checked: a call resolves against
     * the table, wherever in the file order the callee is declared.
     */
    private fun prepareTraitsAndImpls(
        fileScope: FileScope,
        scopes: List<FileScope>,
        program: Ast.Program,
    ) {
        fileImports = fileScope.imports
        structTypes = visibleStructs(fileScope, program)
        traitTypes = visibleTraits(fileScope, program)
        // The enums are known before any written type is resolved: an enum
        // name is a ubyte alias, and `var phase: Status` must resolve to it.
        enumTypes = visibleEnums(fileScope, program)
        (structTypes.keys intersect traitTypes.keys).firstOrNull()?.let { name ->
            throw SemaException("Type name $name is both a struct and a trait")
        }
        (enumTypes.keys intersect (structTypes.keys + traitTypes.keys)).firstOrNull()?.let { name ->
            throw SemaException("Type name $name is both an enum and a struct or trait")
        }
        enumTypes.values.forEach { decl ->
            val name = decl.name!!
            if (name in BUILTIN_TYPE_NAMES || name == "CPU") {
                throw SemaException("Enum name $name is reserved", decl.line)
            }
        }
        traitTypes.values.forEach { checkTraitDecl(it) }
        // Resolve every declaration's written type before any body is
        // checked, so a file that reads another file's global sees a trait
        // type, not a not-yet-retagged struct name. Locals are resolved when
        // their statement is checked.
        fileScope.globals.forEach { resolveType(it.type, it.line) }
        fileScope.functions.forEach { fn ->
            fn.params.forEach { resolveType(it.type) }
            fn.retType?.let { resolveType(it) }
        }

        fileScope.impls.forEach { impl ->
            val struct = structTypes[impl.structName]
                ?: throw SemaException("Unknown struct ${impl.structName} in impl", impl.line)
            val ownerFile = scopes.firstOrNull { scope ->
                scope.structs.any { it === struct }
            }
            if (ownerFile !== fileScope) {
                throw SemaException(
                    "impl for ${struct.name} must be in the same file as the struct",
                    impl.line,
                )
            }
            val trait = impl.traitName?.let { name ->
                traitTypes[name] ?: throw SemaException("Unknown trait $name", impl.line)
            }
            if (trait != null) {
                val implemented = implementedTraits.getOrPut(struct) {
                    java.util.Collections.newSetFromMap(
                        java.util.IdentityHashMap<TraitDecl, Boolean>(),
                    )
                }
                if (!implemented.add(trait)) {
                    throw SemaException(
                        "Duplicate impl of trait ${trait.name} for ${struct.name}",
                        impl.line,
                    )
                }
            }
            impl.methods = effectiveMethods(impl, struct, trait)
            if (trait != null) {
                vtableInfos += Ast.VTableInfo(
                    structName = struct.name,
                    traitName = trait.name,
                    prefix = fileScope.mangledPrefix,
                    arity = impl.methods.size,
                )
            }
            val table = methodsByStruct.getOrPut(struct) { LinkedHashMap() }
            impl.methods.forEach { method ->
                checkMethodSignature(method, struct)
                if (struct.fields.any { it.name == method.name }) {
                    throw SemaException(
                        "Method ${method.name} collides with a field of ${struct.name}",
                        impl.line,
                    )
                }
                if (table.containsKey(method.name)) {
                    throw SemaException(
                        "Duplicate method ${method.name} in struct ${struct.name}",
                        impl.line,
                    )
                }
                val binding = MethodBinding(
                    owner = struct.name,
                    name = method.name,
                    decl = method,
                    prefix = fileScope.mangledPrefix,
                    traitName = trait?.name,
                )
                table[method.name] = binding
                methodBindings += binding
            }
        }
    }

    /** Rejects a trait declaration whose methods cannot be implemented statically. */
    private fun checkTraitDecl(decl: TraitDecl) {
        if (decl.name in BUILTIN_TYPE_NAMES || decl.name == "CPU") {
            throw SemaException("Trait name ${decl.name} is reserved", decl.line)
        }
        val seen = mutableSetOf<String>()
        for (method in decl.methods) {
            if (!seen.add(method.name)) {
                throw SemaException("Duplicate method ${method.name} in trait ${decl.name}", decl.line)
            }
            checkMethodSignature(method, null)
        }
    }

    /** A method signature must fit the receiver-plus-scalars call ABI. */
    private fun checkMethodSignature(method: FunDecl, struct: StructDecl?) {
        if (method.fixedAddress != null) {
            throw SemaException("Method ${method.name} cannot have a fixed address")
        }
        if (method.isInterrupt) {
            throw SemaException("Method ${method.name} cannot be an interrupt handler")
        }
        method.params.forEach { param ->
            resolveType(param.type)
            if (param.location != null) {
                throw SemaException("Method ${method.name} takes no register parameter ${param.name}")
            }
            if (param.type.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: parameter ${param.name}")
            }
        }
        method.retType?.let { ret ->
            resolveType(ret)
            if (ret.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: return type")
            }
            if (ret.kind == TypeKind.TRAIT) {
                throw SemaException("A trait value cannot be returned")
            }
        }
        if (struct != null && method.isAbstract) {
            throw SemaException("Method ${method.name} in impl for ${struct.name} needs a body")
        }
    }

    /**
     * The methods an impl actually provides: its own methods for an inherent
     * impl, or exactly the trait's methods — an override where present, the
     * default body otherwise — for a trait impl.
     */
    private fun effectiveMethods(impl: ImplDecl, struct: StructDecl, trait: TraitDecl?): List<FunDecl> {
        val ownNames = mutableSetOf<String>()
        impl.methods.forEach { method ->
            if (!ownNames.add(method.name)) {
                throw SemaException("Duplicate method ${method.name} in struct ${struct.name}", impl.line)
            }
        }
        if (trait == null) {
            return impl.methods.map { it.copy(owner = struct.name, traitName = null) }
        }
        val traitNames = trait.methods.map { it.name }.toSet()
        impl.methods.firstOrNull { it.name !in traitNames }?.let { extra ->
            throw SemaException(
                "Trait ${trait.name} does not declare method ${extra.name}",
                impl.line,
            )
        }
        val ownByName = impl.methods.associateBy { it.name }
        val effective = mutableListOf<FunDecl>()
        for (traitMethod in trait.methods) {
            val own = ownByName[traitMethod.name]
            if (own == null) {
                if (traitMethod.isAbstract) {
                    throw SemaException(
                        "Impl of trait ${trait.name} for ${struct.name} is missing method ${traitMethod.name}",
                        impl.line,
                    )
                }
                effective += traitMethod.copy(
                    isAbstract = false,
                    owner = struct.name,
                    traitName = trait.name,
                )
            } else {
                if (!sameSignature(traitMethod, own)) {
                    throw SemaException(
                        "Method ${own.name} does not match trait ${trait.name}: " +
                            "expected ${signatureText(traitMethod)}, got ${signatureText(own)}",
                        impl.line,
                    )
                }
                effective += own.copy(owner = struct.name, traitName = trait.name)
            }
        }
        return effective
    }

    private fun sameSignature(a: FunDecl, b: FunDecl): Boolean =
        a.params.size == b.params.size &&
            a.params.zip(b.params).all { (x, y) -> sameType(x.type, y.type) } &&
            sameType(a.retType ?: Types.unit, b.retType ?: Types.unit)

    private fun signatureText(method: FunDecl): String =
        "fun ${method.name}(${method.params.joinToString(", ") { "${it.name}: ${it.type}" }})" +
            (method.retType?.let { ": $it" } ?: "")


    /**
     * Resolves a named type against the visible declarations, retagging a
     * trait name to [TypeKind.TRAIT] in place. Rejects a name no visible
     * declaration provides.
     */
    private fun resolveType(type: WolinType, line: Int? = null) {
        when (type.kind) {
            TypeKind.STRUCT -> {
                val name = type.structName
                if (name != null && enumTypes.containsKey(name)) {
                    type.kind = TypeKind.UBYTE
                    type.structName = null
                    return
                }
                if (name != null && traitTypes.containsKey(name)) {
                    type.kind = TypeKind.TRAIT
                    type.traitName = name
                    return
                }
                if (name == null || structTypes[name] == null) {
                    throw SemaException("Unknown struct $name", line)
                }
            }
            TypeKind.TRAIT -> {
                val name = type.traitName
                if (name == null || traitTypes[name] == null) {
                    throw SemaException("Unknown trait $name", line)
                }
            }
            TypeKind.ARRAY -> {
                type.elementType?.let { resolveType(it, line) }
                type.indexType?.let { resolveType(it, line) }
            }
            TypeKind.FUNCTION -> {
                type.params.forEach { resolveType(it, line) }
                type.retType?.let { resolveType(it, line) }
            }
            else -> Unit
        }
    }

    /** The visible struct a type names, or null when the type is not a struct. */
    private fun structDeclOf(type: WolinType): StructDecl? {
        if (type.kind != TypeKind.STRUCT) return null
        val name = type.structName ?: throw SemaException("Struct type has no name")
        return structTypes[name] ?: throw SemaException("Unknown struct $name")
    }

    /** Checks one file's declarations in its own scope. */
    private fun checkFile(fileScope: FileScope, program: Ast.Program) {
        val globals = Scope()
        scope = globals
        fileImports = fileScope.imports
        structTypes = visibleStructs(fileScope, program)
        traitTypes = visibleTraits(fileScope, program)
        enumTypes = visibleEnums(fileScope, program)
        structTypes.values.forEach { globals.declare(it.name, Symbol.StructSymbol(it)) }
        enumTypes.values.forEach { decl ->
            globals.declare(decl.name!!, Symbol.EnumSymbol(decl))
        }

        // The file's own functions are visible everywhere inside it, so they
        // are declared before anything is checked — a call may name a
        // function declared further down.
        fileScope.functions.forEach { fn ->
            globals.declare(fn.name, Symbol.FunSymbol(fn, fileScope.mangledPrefix))
        }
        // A declaration of another file in the same package is visible
        // without an import; one from another package is not. These are
        // declared first: the file's own declarations shadow them.
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other ->
                other.functions.forEach { fn ->
                    globals.declare(fn.name, Symbol.FunSymbol(fn, other.mangledPrefix))
                }
                other.globals.forEach { global ->
                    globals.declare(
                        global.name,
                        Symbol.DataSymbol(
                            global.type, global.location, global.init, global.isVal,
                            other.mangledPrefix, global.enumValue,
                        ),
                    )
                }
            }
        fileScope.globals.forEach { global ->
            globals.declare(
                global.name,
                Symbol.DataSymbol(
                    global.type, global.location, global.init, global.isVal,
                    fileScope.mangledPrefix, global.enumValue,
                ),
            )
        }
        // An import brings a name from another package into scope; without
        // the import the name does not resolve at all.
        fileScope.imports.forEach { import ->
            if (import.alias != null) {
                throw SemaException("import aliases are not supported in v1")
            }
            if (import.path.endsWith(".*")) {
                // A wildcard import is valid when the package exists; the
                // names it brings resolve at the use site.
                val packageName = import.path.removeSuffix(".*")
                if (program.files.none { it.packageName == packageName }) {
                    throw SemaException("Cannot resolve import ${import.path}")
                }
                return@forEach
            }
            val imported = import.path.substringAfterLast('.')
            if (imported.isEmpty() || imported == import.path) return@forEach
            // A trait is not a value and is not declared in the value scope;
            // it is resolved through [traitTypes] for impls.
            if (traitTypes.containsKey(imported)) return@forEach
            val symbol = importResolver.resolve(imported, listOf(import), program)
                ?: throw SemaException("Cannot resolve import ${import.path}")
            globals.declare(imported, symbol)
        }
        // The constant value of every global checked so far: a global's
        // initialiser may reference only globals declared before it. The
        // constants of the other files of the same package come first: they
        // are initialised before this file's, in command-line order.
        val constSoFar = LinkedHashMap<String, ConstValue>()
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .flatMap { it.globals }
            .forEach { global ->
                global.constValue?.let { constSoFar[global.name] = it }
            }
        // The functions this file can name, with the prefix of the file that
        // declares each: a function of another file is emitted under that
        // file's prefix.
        val functionNames = mutableMapOf<String, String>()
        fileScope.functions.forEach { fn -> functionNames[fn.name] = fileScope.mangledPrefix }
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other ->
                other.functions.forEach { fn -> functionNames[fn.name] = other.mangledPrefix }
            }
        fileScope.imports.forEach { import ->
            val imported = import.path.removeSuffix(".*").substringAfterLast('.', "")
            if (imported.isEmpty()) return@forEach
            val scope = program.files.firstOrNull {
                it.packageName == import.path.removeSuffix(".*").substringBeforeLast('.', "") &&
                    it.functions.any { fn -> fn.name == imported }
            }
            if (scope != null) functionNames[imported] = scope.mangledPrefix
        }
        fileScope.globals.forEachIndexed { index, global ->
            checkGlobal(global, fileScope, index, constSoFar, functionNames)
        }
        fileScope.functions.forEach { fn -> checkFunction(fn, globals) }
        fileScope.impls.forEach { impl ->
            impl.methods.forEach { method -> checkMethod(method, globals) }
        }
    }
    // ---- declarations -----------------------------------------------------

    private fun checkGlobal(
        global: GlobalDecl,
        fileScope: FileScope,
        index: Int,
        constSoFar: MutableMap<String, ConstValue>,
        functionNames: Map<String, String>,
    ) {
        val declared = global.declaredType
        // Resolve the annotation first: a named enum is a ubyte alias, and
        // `var s: Status = Status.PENDING` must compare ubyte with ubyte.
        declared?.let { resolveType(it, global.line) }
        if (global.init != null) {
            // The ordering error must beat the plain "unknown variable" the
            // reference check below would report for the same name.
            val laterNames = fileScope.globals.drop(index + 1).map { it.name }.toSet()
            varRefs(global.init!!).firstOrNull { it.name in laterNames }?.let {
                throw SemaException("initialiser references a global that is not yet initialised")
            }
        }
        val initType = global.init?.let { ini ->
            if (declared?.kind == TypeKind.TRAIT) {
                checkTraitValue(ini, declared)
                declared
            } else if (ini is Ast.ArrayLiteral) {
                if (declared == null) throw SemaException("Array literal needs an array type")
                if (declared?.kind != TypeKind.ARRAY) throw SemaException("${declared} is not an array type")
                global.init = checkArrayInit(ini, declared)
                declared
            } else if (ini is StringLit && declared?.kind == TypeKind.ARRAY) {
                global.init = checkArrayInit(ini, declared)
                declared
            } else checkExpr(ini, declared)
        }
        if (declared != null && initType != null && !sameType(initType, declared)) {
            throw SemaException("Attempt reassign $declared $initType")
        }
        val type = declared ?: initType
            ?: throw SemaException("Variable ${global.name} has no type!")
        global.type = type
        resolveType(type, global.line)
        validateArrayType(type, global.location, global.name, global.line)
        rejectCpuRegisterVariable(global.name, global.location, type, global.line)
        if (global.init != null && type.kind != TypeKind.TRAIT) {
            global.constValue = ConstFolder.fold(global.init!!, constSoFar, functionNames)
                ?: throw SemaException("global initialiser must be a constant expression")
        }
        scope.declare(
            global.name,
            Symbol.DataSymbol(
                type, global.location, global.init, global.isVal,
                enumValue = global.enumValue,
            ),
        )
        val constValue = global.constValue
        if (constValue != null) constSoFar[global.name] = constValue
    }

    /** Every variable reference in [expr], in source order. */
    private fun varRefs(expr: Expr, out: MutableList<VarRef> = mutableListOf()): List<VarRef> {
        when (expr) {
            is VarRef -> out += expr
            is UnaryOp -> varRefs(expr.operand, out)
            is BinaryOp -> { varRefs(expr.left, out); varRefs(expr.right, out) }
            is Call -> { varRefs(expr.callee, out); expr.args.forEach { varRefs(it, out) } }
            is ArrayAccess -> { varRefs(expr.array, out); varRefs(expr.index, out) }
            is Assign -> { varRefs(expr.target, out); varRefs(expr.value, out) }
            is IfExpr -> {
                varRefs(expr.cond, out)
                expr.then.forEach { walkStmtVarRefs(it, out) }
                expr.otherwise.forEach { walkStmtVarRefs(it, out) }
            }
            is WhenExpr -> {
                expr.subject?.let { varRefs(it, out) }
                expr.entries.forEach { entry ->
                    entry.conditions.forEach { varRefs(it, out) }
                    entry.body.forEach { walkStmtVarRefs(it, out) }
                }
            }
            is Convert -> varRefs(expr.value, out)
            is ArrayLiteral -> expr.elements.forEach { varRefs(it, out) }
            is StringTemplate -> expr.parts.forEach {
                if (it is Ast.StringTemplate.Value) varRefs(it.expr, out)
            }
            else -> Unit
        }
        return out
    }

    private fun walkStmtVarRefs(stmt: Stmt, out: MutableList<VarRef>) {
        when (stmt) {
            is LocalDecl -> stmt.init?.let { varRefs(it, out) }
            is ExprStmt -> varRefs(stmt.expr, out)
            is BlockStmt -> stmt.body.forEach { walkStmtVarRefs(it, out) }
            is IfStmt -> {
                varRefs(stmt.cond, out)
                stmt.then.forEach { walkStmtVarRefs(it, out) }
                stmt.otherwise.forEach { walkStmtVarRefs(it, out) }
            }
            is WhileStmt -> {
                varRefs(stmt.cond, out)
                stmt.body.forEach { walkStmtVarRefs(it, out) }
            }
            is ReturnStmt -> stmt.value?.let { varRefs(it, out) }
            is WhenStmt -> {
                stmt.subject?.let { varRefs(it, out) }
                stmt.entries.forEach { entry ->
                    entry.conditions.forEach { varRefs(it, out) }
                    entry.body.forEach { walkStmtVarRefs(it, out) }
                }
            }
            else -> Unit
        }
    }

    private fun checkFunction(fn: FunDecl, globals: Scope) {
        if (fn.isInterrupt) {
            if (fn.params.isNotEmpty()) {
                throw SemaException("Arguments not allowed for interrupt function!")
            }
            if (fn.retType != null) {
                throw SemaException("Return not allowed for interrupt function!")
            }
        }
        if (fn.isExternal && fn.isInterrupt) {
            throw SemaException(
                "An external function cannot be an interrupt handler: ${fn.name}",
            )
        }
        fn.params.forEach { param ->
            resolveType(param.type)
            if (param.type.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: parameter ${param.name}")
            }
        }
        fn.retType?.let { ret ->
            resolveType(ret)
            if (ret.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: return type")
            }
            if (ret.kind == TypeKind.TRAIT) {
                throw SemaException("A trait value cannot be returned")
            }
        }
        if (fn.fixedAddress != null) checkFixedAddressFunction(fn)
        val outer = scope
        val functionScope = globals.child()
        scope = functionScope
        currentReturnType = fn.retType
        currentFunctionName = fn.name
        val outerThis = currentThis
        currentThis = null
        try {
            currentLabels = collectLabels(fn.body)
            fn.params.forEach { param ->
                functionScope.declare(param.name, Symbol.DataSymbol(param.type, param.location, null))
            }
            fn.body.forEach { checkStmt(it) }
        } finally {
            scope = outer
            currentReturnType = null
            currentFunctionName = ""
            currentLabels = null
            currentThis = outerThis
        }
    }

    /**
     * Checks one impl method. The receiver is not a parameter: `this` names
     * the struct storage the caller passed, and field access is an ordinary
     * member access on it.
     */
    private fun checkMethod(fn: FunDecl, globals: Scope) {
        val ownerName = fn.owner
            ?: throw SemaException("Method ${fn.name} has no struct")
        val struct = structTypes[ownerName]
            ?: throw SemaException("Unknown struct $ownerName")
        fn.params.forEach { param ->
            resolveType(param.type)
            if (param.type.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: parameter ${param.name}")
            }
        }
        fn.retType?.let { ret ->
            resolveType(ret)
            if (ret.kind == TypeKind.STRUCT) {
                throw SemaException("A struct value is not first class: return type")
            }
            if (ret.kind == TypeKind.TRAIT) {
                throw SemaException("A trait value cannot be returned")
            }
        }
        val outer = scope
        val methodScope = globals.child()
        scope = methodScope
        currentReturnType = fn.retType
        currentFunctionName = fn.name
        val outerThis = currentThis
        currentThis = struct
        try {
            currentLabels = collectLabels(fn.body)
            fn.params.forEach { param ->
                methodScope.declare(param.name, Symbol.DataSymbol(param.type, param.location, null))
            }
            fn.body.forEach { checkStmt(it) }
        } finally {
            scope = outer
            currentReturnType = null
            currentFunctionName = ""
            currentLabels = null
            currentThis = outerThis
        }
    }

    /**
     * The rules for a fixed-address function: a declaration of a routine
     * that already lives somewhere else — a KERNAL routine, for instance.
     *
     * Every parameter names the register its argument arrives in, and a
     * return type names the register the result arrives in. A `ubyte` result
     * comes from one register (A, X or Y); a `uword` result comes from a
     * pair (AX or XY), whose high byte is X in both.
     */
    private fun checkFixedAddressFunction(fn: FunDecl) {
        for (param in fn.params) {
            val register = param.location as? CpuRegister
                ?: throw SemaException(
                    "Fixed address function ${fn.name} needs a CPU register parameter"
                )
            if (!isSingleRegister(register.name)) {
                throw SemaException(
                    "Fixed address function ${fn.name} needs a single register parameter, not ${register.name}"
                )
            }
        }
        val retType = fn.retType ?: return
        when (retType.kind) {
            // A bool result is the Carry flag: the routine signals success or
            // failure in CPU.C, so no register is named (an explicit location,
            // if given, must be CPU.C).
            TypeKind.BOOL -> {
                val location = fn.retLocation as? CpuRegister
                if (location != null && location.name != "CPU.C") {
                    throw SemaException(
                        "Fixed address function ${fn.name} returns a bool from the Carry " +
                            "flag; a location must be CPU.C, not ${location.name}"
                    )
                }
            }
            TypeKind.UBYTE -> {
                val register = fn.retLocation as? CpuRegister
                    ?: throw SemaException(
                        "Fixed address function ${fn.name} needs a CPU register return type"
                    )
                if (!isSingleRegister(register.name)) {
                    throw SemaException(
                        "Fixed address function ${fn.name} returns one register, not ${register.name}"
                    )
                }
            }
            TypeKind.UWORD -> {
                val register = fn.retLocation as? CpuRegister
                    ?: throw SemaException(
                        "Fixed address function ${fn.name} needs a CPU register return type"
                    )
                if (register.name != "CPU.AX" && register.name != "CPU.XY") {
                    throw SemaException(
                        "Fixed address function ${fn.name} returns a register pair, not ${register.name}"
                    )
                }
            }
            else -> throw SemaException(
                "Fixed address function ${fn.name} can only return ubyte, uword or bool, not $retType"
            )
        }
    }

    /** Whether a `CPU.*` name is one of the single registers A, X and Y. */
    private fun isSingleRegister(name: String): Boolean =
        name == "CPU.A" || name == "CPU.X" || name == "CPU.Y"

    /**
     * A CPU-located variable (e.g. `var x: ubyte^CPU.X`) is rejected in v1:
     * the compiler owns the CPU registers, so a value written to one does not
     * survive unrelated code. The value must be read back through a
     * fixed-address function return type, or passed as a parameter.
     */
    private fun rejectCpuRegisterVariable(name: String, location: Location?, type: WolinType, line: Int?) {
        if (location is CpuRegister) {
            // A flag register is a single bit of P that the compiler can
            // read and write without clobbering an accumulator: `sei`/`cli`
            // (interrupt) and `sec`/`clc` (carry) set and clear it, so the
            // value survives. Only the flags with a set and a clear
            // instruction qualify.
            val isFlag = location.name in setOf("CPU.C", "CPU.I")
            if (isFlag && type.kind == TypeKind.BOOL) return
            throw SemaException(
                "CPU register variable $name is not supported yet: " +
                    "the compiler owns the CPU registers, so a value written to " +
                    "one does not survive other code. Return it from a " +
                    "fixed-address function instead (e.g. " +
                    "`fun rdtim^0xffde(): ubyte^CPU.A`), or pass it as a parameter.",
                line,
            )
        }
    }

    // ---- statements -------------------------------------------------------

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is LocalDecl -> {
                val declared = stmt.declaredType
                declared?.let { resolveType(it, stmt.line) }
                val init = stmt.init
                val initType = init?.let { ini ->
                    if (declared?.kind == TypeKind.TRAIT) {
                        checkTraitValue(ini, declared)
                        declared
                    } else if (ini is Ast.ArrayLiteral) {
                        if (declared == null) throw SemaException("Array literal needs an array type")
                        if (declared?.kind != TypeKind.ARRAY) throw SemaException("${declared} is not an array type")
                        if (stmt.location !is FixedArray) {
                            throw SemaException("local arrays need a fixed address in v1")
                        }
                        stmt.init = checkArrayInit(ini, declared)
                        declared
                    } else if (ini is StringLit && declared?.kind == TypeKind.ARRAY) {
                        if (stmt.location !is FixedArray) {
                            throw SemaException("local arrays need a fixed address in v1")
                        }
                        stmt.init = checkArrayInit(ini, declared)
                        declared
                    } else checkExpr(ini, declared)
                }
                if (declared != null && initType != null && !sameType(initType, declared)) {
                    throw SemaException("Attempt reassign $declared $initType")
                }
                val type = declared ?: initType
                    ?: throw SemaException("Variable ${stmt.name} has no type!")
                stmt.type = type
                resolveType(type, stmt.line)
                if (type.kind == TypeKind.ARRAY && stmt.location !is FixedArray) {
                    throw SemaException("local arrays need a fixed address in v1", stmt.line)
                }
                validateArrayType(type, stmt.location, stmt.name, stmt.line)
                rejectCpuRegisterVariable(stmt.name, stmt.location, type, stmt.line)
                scope.declare(stmt.name, Symbol.DataSymbol(type, stmt.location, stmt.init, stmt.isVal))
            }

            is ExprStmt -> checkExpr(stmt.expr, null)

            is BlockStmt -> {
                val outer = scope
                scope = scope.child()
                try {
                    checkBranch(stmt.body, null)
                } finally {
                    scope = outer
                }
            }

            is IfStmt -> {
                requireBool(checkExpr(stmt.cond, null), "if")
                checkBranch(stmt.then, null)
                checkBranch(stmt.otherwise, null)
            }

            is WhileStmt -> {
                requireBool(checkExpr(stmt.cond, null), "while")
                checkBranch(stmt.body, null)
            }

            is ReturnStmt -> {
                val expected = currentReturnType
                val value = stmt.value
                if (value == null) {
                    if (expected != null) {
                        throw SemaException("Function $currentFunctionName must return a value")
                    }
                } else {
                    val valueType = checkExpr(value, expected)
                    if (expected == null) {
                        throw SemaException("Unit function $currentFunctionName has no return type")
                    }
                    if (!sameType(valueType, expected)) {
                        throw SemaException("Attempt reassign $expected $valueType")
                    }
                }
            }

            is WhenStmt -> checkWhen(stmt)
            is WhenEntry -> throw SemaException("A when entry cannot appear outside a when")

            is ReturnAtStmt -> (stmt.target as? ReturnAtLabel)?.let { requireLabel(it.name) }
            is BreakStmt -> stmt.label?.let { requireLabel(it) }
            is ContinueStmt -> stmt.label?.let { requireLabel(it) }
            is LabelStmt -> Unit
        }
    }

    /**
     * The labels defined by [stmts], in source order: the statement walk of
     * [checkStmt], with [FunctionLit] as a boundary — a literal's body is a
     * different function. Throws on a duplicate name.
     */
    private fun collectLabels(stmts: List<Stmt>): Set<String> {
        val out = mutableSetOf<String>()
        stmts.forEach { visitLabelStmts(it, out) }
        return out
    }

    private fun visitLabelStmts(s: Stmt, out: MutableSet<String>) {
        when (s) {
            is LabelStmt ->
                if (!out.add(s.name)) throw SemaException("Label ${s.name} already defined")
            is LocalDecl -> s.init?.let { visitLabelExpr(it, out) }
            is BlockStmt -> s.body.forEach { visitLabelStmts(it, out) }
            is IfStmt -> {
                visitLabelExpr(s.cond, out)
                s.then.forEach { visitLabelStmts(it, out) }
                s.otherwise.forEach { visitLabelStmts(it, out) }
            }
            is WhileStmt -> {
                visitLabelExpr(s.cond, out)
                s.body.forEach { visitLabelStmts(it, out) }
            }
            is WhenStmt -> {
                s.subject?.let { visitLabelExpr(it, out) }
                s.entries.forEach { entry ->
                    entry.conditions.forEach { visitLabelExpr(it, out) }
                    entry.body.forEach { visitLabelStmts(it, out) }
                }
            }
            is ExprStmt -> visitLabelExpr(s.expr, out)
            is ReturnStmt -> s.value?.let { visitLabelExpr(it, out) }
            else -> Unit
        }
    }

    private fun visitLabelExpr(e: Expr, out: MutableSet<String>) {
        when (e) {
            is IfExpr -> {
                visitLabelExpr(e.cond, out)
                e.then.forEach { visitLabelStmts(it, out) }
                e.otherwise.forEach { visitLabelStmts(it, out) }
            }
            is WhenExpr -> {
                e.subject?.let { visitLabelExpr(it, out) }
                e.entries.forEach { entry ->
                    entry.conditions.forEach { visitLabelExpr(it, out) }
                    entry.body.forEach { visitLabelStmts(it, out) }
                }
            }
            is UnaryOp -> visitLabelExpr(e.operand, out)
            is BinaryOp -> {
                visitLabelExpr(e.left, out)
                visitLabelExpr(e.right, out)
            }
            is Call -> {
                visitLabelExpr(e.callee, out)
                e.args.forEach { visitLabelExpr(it, out) }
            }
            is Assign -> {
                visitLabelExpr(e.target, out)
                visitLabelExpr(e.value, out)
            }
            is ArrayAccess -> {
                visitLabelExpr(e.array, out)
                visitLabelExpr(e.index, out)
            }
            is StringTemplate -> e.parts.forEach {
                if (it is Ast.StringTemplate.Value) visitLabelExpr(it.expr, out)
            }
            is Convert -> visitLabelExpr(e.value, out)
            is ArrayLiteral -> e.elements.forEach { visitLabelExpr(it, out) }
            else -> Unit
        }
    }

    private fun requireLabel(name: String) {
        if (name !in (currentLabels ?: emptySet())) {
            throw SemaException("Unknown label $name")
        }
    }

    private fun checkWhen(stmt: WhenStmt) {
        checkWhenEntries(stmt.subject, stmt.entries) { entry -> checkBranch(entry.body, null) }
    }

    private fun checkWhenExpr(expr: WhenExpr, expected: WolinType?): WolinType {
        val branchTypes = checkWhenEntries(expr.subject, expr.entries) { entry ->
            checkBranch(entry.body, expected)
        }
        if (expr.entries.none { it.isElse }) {
            throw SemaException("this when expression may not match any branch")
        }
        expr.type = if (branchTypes.all { sameType(it, branchTypes.firstOrNull() ?: Types.unit) }) {
            branchTypes.firstOrNull() ?: Types.unit
        } else {
            Types.unit
        }
        return expr.type
    }

    /**
     * Checks a `when`'s subject and entries: the subject's comparability, the
     * else ordering, and each condition's type. [checkBranch] is called on
     * every entry body and its result collected; the statement form discards
     * them, the expression form unifies them into the value's type.
     */
    private fun checkWhenEntries(
        subject: Expr?,
        entries: List<WhenEntry>,
        checkBranch: (WhenEntry) -> WolinType,
    ): List<WolinType> {
        val subjectType = subject?.let { checkExpr(it, null) }
        if (subjectType != null && (subjectType.kind == TypeKind.UNIT || subjectType.kind == TypeKind.STRING)) {
            throw SemaException("when subject must be comparable, not $subjectType")
        }
        var seenElse = false
        val branchTypes = mutableListOf<WolinType>()
        entries.forEach { entry ->
            if (entry.isElse) {
                if (seenElse) throw SemaException("A when can have only one else branch")
                seenElse = true
            } else {
                if (seenElse) throw SemaException("The else branch of a when must be last")
                entry.conditions.forEach { condition ->
                    // A literal takes the subject's type, so `when (x: uword) { 1 -> }`
                    // compares `uword`s, exactly like `x == 1`.
                    val condType = if (isContextualIntLiteral(condition) && subjectType != null && subjectType.isInteger) {
                        checkExpr(condition, subjectType)
                    } else {
                        checkExpr(condition, null)
                    }
                    if (subjectType != null) {
                        if (condType != subjectType) {
                            throw SemaException("when condition type $condType does not match subject type $subjectType")
                        }
                    } else {
                        requireBool(condType, "when condition")
                    }
                }
            }
            branchTypes += checkBranch(entry)
        }
        return branchTypes
    }

    /**
     * Checks a list of statements and returns the type of the value they
     * produce — the type of the last expression, or `unit` when the branch
     * does not end in an expression.
     */
    private fun checkBranch(stmts: List<Stmt>, expected: WolinType?): WolinType {
        if (stmts.isEmpty()) return Types.unit
        stmts.dropLast(1).forEach { checkStmt(it) }
        return when (val last = stmts.last()) {
            is ExprStmt -> checkExpr(last.expr, expected)
            is BlockStmt -> {
                val outer = scope
                scope = scope.child()
                try {
                    checkBranch(last.body, expected)
                } finally {
                    scope = outer
                }
            }
            else -> {
                checkStmt(last)
                Types.unit
            }
        }
    }

    // ---- expressions ------------------------------------------------------

    /**
     * Types and validates an array initialiser against its declared array
     * type. A string initialiser is converted into byte elements here, so
     * every later stage sees an [ArrayLiteral] of integer elements.
     *
     * Returns the (possibly rewritten) initialiser expression.
     */
    private fun checkArrayInit(init: Expr, type: WolinType?): Expr {
        val ety = type?.elementType ?: throw SemaException("Array literal needs an array type")
        if (ety.kind == TypeKind.STRUCT) {
            throw SemaException("Struct array initialisers are not supported")
        }
        val length = arrayLength(type)
        val elements: List<Expr> = when (init) {
            is Ast.ArrayLiteral -> {
                if (init.elements.size > length) {
                    throw SemaException("Too many array elements: ${init.elements.size} for a $length-element array")
                }
                init.elements.forEach { e ->
                    checkExpr(e, ety)
                }
                init.elements
            }
            is StringLit -> {
                if (ety.kind != TypeKind.UBYTE) {
                    throw SemaException("String initialiser needs a ubyte element type")
                }
                val bytes = init.bytes
                if (bytes.size + 1 > length) {
                    throw SemaException("String initialiser too long: ${bytes.size + 1} for a $length-element array")
                }
                bytes.map { IntLit(it.toLong(), ety) } + IntLit(0, ety)
            }
            else -> throw SemaException("Array literal needs an array type")
        }
        return Ast.ArrayLiteral(elements, type!!)
    }

    /**
     * Checks an expression. A struct-typed expression is not a value: only a
     * member access may ask for one ([allowStruct]) and then read a field.
     */
    private fun checkExpr(expr: Expr, expected: WolinType?, allowStruct: Boolean = false): WolinType {
        val type = when (expr) {
        is IntLit -> literalType(expr.value, expected).also { expr.type = it }
        is RealLit -> Types.float.also { expr.type = it }
        is BoolLit -> Types.bool.also { expr.type = it }
        is CharLit -> Types.ubyte.also { expr.type = it }
        is StringLit -> Types.string.also { expr.type = it }
        is StringTemplate -> checkStringTemplate(expr)
        is NullLit -> WolinType(TypeKind.UNIT, nullable = true).also { expr.type = it }
        is VarRef -> checkVarRef(expr)
        is ThisRef -> checkThis(expr)
        is UnaryOp -> checkUnary(expr, expected)
        is BinaryOp -> checkBinary(expr)
        is Assign -> checkAssign(expr)
        is Call -> checkCall(expr, expected)
        is FunctionLit -> checkFunctionLiteral(expr, expected)
        is IfExpr -> checkIfExpr(expr, expected)
        is WhenExpr -> checkWhenExpr(expr, expected)
        is ArrayAccess -> checkArrayAccess(expr)
        is MemberAccess -> checkMemberAccess(expr)
        is Convert -> checkConvert(expr)
        is ArrayLiteral -> throw SemaException("Array literal can only initialise a declaration")
        }
        if (type.kind == TypeKind.STRUCT && !allowStruct) {
            throw SemaException("A struct value is not first class")
        }
        return type
    }

    /**
     * Checks each interpolation part and bounds the fixed text. A `string`
     * part contributes no fixed bytes: its runtime length is capped by the
     * 255-byte result buffer at emit time.
     */
    private fun checkStringTemplate(expr: StringTemplate): WolinType {
        var fixed = 0
        for (part in expr.parts) when (part) {
            is Ast.StringTemplate.Literal -> {
                if (part.bytes.any { it == 0.toByte() }) {
                    throw SemaException("NUL is not allowed in a string template")
                }
                fixed += part.bytes.size
            }
            is Ast.StringTemplate.Value -> {
                val type = checkExpr(part.expr, null)
                fixed += when (type.kind) {
                    TypeKind.UBYTE -> 3
                    TypeKind.BYTE -> 4
                    TypeKind.UWORD -> 5
                    TypeKind.WORD -> 6
                    TypeKind.BOOL -> 5
                    TypeKind.STRING -> 0
                    TypeKind.FLOAT -> 16
                    else -> throw SemaException(
                        "Unsupported construct: $type in string interpolation",
                    )
                }
            }
        }
        if (fixed > 255) {
            throw SemaException("String interpolation is limited to 255 bytes")
        }
        return Types.string.also { expr.type = it }
    }

    /**
     * The symbol [name] refers to: the scope first, then the imports.
     *
     * A wildcard import (`import a.b.*`) brings in names that are not known
     * until one is used, so the import is consulted when the scope has no
     * declaration of the name.
     */
    private fun lookup(name: String): Symbol? {
        val symbol = scope.lookup(name)
        if (symbol != null) return symbol
        val program = program ?: return null
        return importResolver.resolve(name, fileImports, program)
    }

    private fun checkVarRef(expr: VarRef): WolinType {
        val symbol = lookup(expr.name)
            ?: throw SemaException("Unknown variable ${expr.name}")
        when (symbol) {
            is Symbol.DataSymbol -> expr.type = symbol.type
            // A function name used as a value is its address, which is a `uword`.
            is Symbol.FunSymbol -> expr.type = Types.uword
            is Symbol.StructSymbol ->
                throw SemaException("Struct ${symbol.decl.name} is a type, not a value")
            is Symbol.EnumSymbol ->
                throw SemaException("Enum ${symbol.decl.name} is a type, not a value")
        }
        resolved[expr] = symbol
        return expr.type
    }

    /** `this` is the receiver storage of the impl method being checked. */
    private fun checkThis(expr: ThisRef): WolinType {
        val struct = currentThis
            ?: throw SemaException("this is only available in an impl method")
        expr.type = Types.struct(struct.name)
        return expr.type
    }

    /**
     * Whether [expr] is an integer literal that takes its type from context: a
     * literal, or one with a single sign prefix. The `-`/`+` unary arms type such
     * an operand from the expected type, so `wordValue == -1` compares `word`s,
     * exactly like `wordValue == 1`.
     */
    private fun isContextualIntLiteral(expr: Expr): Boolean =
        expr is IntLit ||
            (expr is UnaryOp && (expr.op == "-" || expr.op == "+") && expr.operand is IntLit)

    private fun checkUnary(expr: UnaryOp, expected: WolinType?): WolinType {
        when (expr.op) {
            "-" -> {
                val operand = expr.operand
                if (operand is IntLit) {
                    val type = literalType(-operand.value, expected)
                    operand.type = type
                    expr.type = type
                } else {
                    val type = checkExpr(operand, null)
                    requireNumeric(type, expr.op)
                    expr.type = type
                }
            }

            "+" -> {
                val operand = expr.operand
                if (operand is IntLit) {
                    val type = literalType(operand.value, expected?.takeIf { it.isInteger })
                    operand.type = type
                    expr.type = type
                } else {
                    val type = checkExpr(operand, null)
                    requireNumeric(type, expr.op)
                    expr.type = type
                }
            }

            "!" -> {
                requireBool(checkExpr(expr.operand, null), expr.op)
                expr.type = Types.bool
            }

            "++", "--" -> {
                val type = checkExpr(expr.operand, null)
                if (!type.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $type")
                }
                expr.type = type
            }

            else -> throw SemaException("Unknown operator ${expr.op}")
        }
        return expr.type
    }

    private fun checkBinary(expr: BinaryOp): WolinType {
        val left = expr.left
        val right = expr.right

        // A literal takes the type of the operand it is combined with, so
        // `uwordValue + 1` is a `uword` operation and not a type mismatch.
        // A bitmask is a bit pattern, so it may use the operand's full
        // width: `wordValue & 0xFF00` keeps the high byte.
        val bitwise = expr.op == "&" || expr.op == "|" || expr.op == "xor"
        val leftType: WolinType
        val rightType: WolinType
        when {
            left is IntLit && right !is IntLit && right !is RealLit -> {
                rightType = checkExpr(right, null)
                leftType = checkContextualIntLiteral(left, rightType, bitwise)
            }
            right is IntLit && left !is IntLit && left !is RealLit -> {
                leftType = checkExpr(left, null)
                rightType = checkContextualIntLiteral(right, leftType, bitwise)
            }
            isContextualIntLiteral(right) && !isContextualIntLiteral(left) && left !is RealLit -> {
                leftType = checkExpr(left, null)
                rightType = if (leftType.isInteger) checkExpr(right, leftType) else checkExpr(right, null)
            }
            isContextualIntLiteral(left) && !isContextualIntLiteral(right) && right !is RealLit -> {
                rightType = checkExpr(right, null)
                leftType = if (rightType.isInteger) checkExpr(left, rightType) else checkExpr(left, null)
            }
            else -> {
                leftType = checkExpr(left, null)
                rightType = checkExpr(right, null)
            }
        }

        if (leftType.kind == TypeKind.TRAIT || rightType.kind == TypeKind.TRAIT) {
            throw SemaException("Operator ${expr.op} is not defined for trait values")
        }
        when (expr.op) {
            "&&", "||" -> {
                requireBool(leftType, expr.op)
                requireBool(rightType, expr.op)
                expr.type = Types.bool
            }

            "==", "!=" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                expr.type = Types.bool
            }

            "<", "<=", ">", ">=" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                requireNumeric(leftType, expr.op)
                requireNumeric(rightType, expr.op)
                expr.type = Types.bool
            }

            "+", "-", "*", "/", "%" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                requireNumeric(leftType, expr.op)
                requireNumeric(rightType, expr.op)
                expr.type = leftType
            }

            "&", "|", "xor" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                if (!leftType.isBitwise) {
                    throw SemaException("Operator ${expr.op} is not defined for $leftType")
                }
                expr.type = leftType
            }

            else -> throw SemaException("Unknown operator ${expr.op}")
        }
        return expr.type
    }

    private fun checkAssign(expr: Assign): WolinType {
        if (expr.value is Ast.ArrayLiteral) {
            throw SemaException("Array literal can only initialise a declaration")
        }
        when (val target = expr.target) {
            is VarRef -> {
                val symbol = lookup(target.name)
                    ?: throw SemaException("Unknown variable ${target.name}")
                val data = symbol as? Symbol.DataSymbol
                    ?: throw SemaException("Cannot assign to function ${target.name}")
                if (data.isVal) {
                    throw SemaException("Cannot assign to val ${target.name}")
                }
                resolved[target] = symbol
                target.type = data.type

                checkAssignedValue(expr, data.type)
                expr.type = data.type
            }

            is ArrayAccess -> {
                val arrayType = checkExpr(target.array, null)
                if (arrayType.kind != TypeKind.ARRAY) {
                    throw SemaException("Cannot assign to a value that is not an array")
                }
                val indexType = checkExpr(target.index, null)
                if (!indexType.isInteger) {
                    throw SemaException("Array index must be an integer, not $indexType")
                }
                target.type = arrayType.elementType ?: Types.unit
                if (target.type.kind == TypeKind.STRUCT) {
                    throw SemaException("Cannot assign a struct value; assign a field instead")
                }

                checkAssignedValue(expr, target.type)
                expr.type = target.type
            }

            is MemberAccess -> {
                val receiver = target.receiver
                if (receiver is VarRef) {
                    val symbol = lookup(receiver.name)
                    if (symbol is Symbol.EnumSymbol) {
                        throw SemaException(
                            "Cannot assign to enum entry ${symbol.decl.name}.${target.name}"
                        )
                    }
                }
                val receiverType = checkExpr(target.receiver, null, allowStruct = true)
                val owner = structDeclOf(receiverType)
                    ?: throw SemaException("Cannot access member ${target.name} of $receiverType")
                val field = owner.fields.firstOrNull { it.name == target.name }
                    ?: throw SemaException("Unknown field ${target.name} in ${owner.name}")
                target.owner = owner
                target.type = field.type
                checkAssignedValue(expr, field.type)
                expr.type = field.type
            }

            else -> throw SemaException("Invalid assignment target")
        }
        return expr.type
    }

    /**
     * Checks a value assigned to a trait-typed storage: either another trait
     * value of the same trait, or a struct that implements the trait.
     */
    private fun checkTraitValue(value: Expr, target: WolinType) {
        val traitName = target.traitName
            ?: throw SemaException("Trait type has no name")
        val valueType = checkExpr(value, null, allowStruct = true)
        when (valueType.kind) {
            TypeKind.TRAIT -> {
                if (valueType.traitName != traitName) {
                    throw SemaException("Attempt reassign $target $valueType")
                }
            }
            TypeKind.STRUCT -> {
                val owner = structDeclOf(valueType)
                    ?: throw SemaException("Unknown struct $valueType")
                if (!implementsTrait(owner, traitName)) {
                    throw SemaException("Struct ${owner.name} does not implement trait $traitName")
                }
            }
            else -> throw SemaException("Attempt reassign $target $valueType")
        }
    }

    /** Whether [struct] has an `impl Struct : Trait` for [traitName]. */
    private fun implementsTrait(struct: StructDecl, traitName: String): Boolean =
        implementedTraits[struct]?.any { it.name == traitName } == true

    /**
     * Checks the value of an assignment against the target's type, according to
     * the assignment operator:
     *
     * - `=` — the value's type must equal the target's type.
     * - `+=`, `-=`, `*=`, `/=`, `%=` — read-modify-write: the arithmetic rule
     *   applies, so the value has the target's type. An integer literal takes
     *   the target's type contextually; any other value of a different type is
     *   an error, because there is no promotion.
     * - `:=`, `.=`, `&=`, `|=` — a bit mask: the value is an integer, but it
     *   need not have the target's type; it only has to fit the target's width.
     */
    private fun checkAssignedValue(expr: Assign, targetType: WolinType) {
        if (targetType.kind == TypeKind.TRAIT) {
            if (expr.op != "=") {
                throw SemaException("Operator ${expr.op} is not defined for $targetType")
            }
            checkTraitValue(expr.value, targetType)
            return
        }
        when (expr.op) {
            "=" -> {
                val valueType = checkExpr(expr.value, targetType)
                if (!sameType(valueType, targetType)) {
                    throw SemaException("Attempt reassign $targetType $valueType")
                }
            }

            "+=", "-=", "*=", "/=", "%=" -> {
                requireNumeric(targetType, expr.op)
                // An integer literal takes an *integer* target's type
                // contextually. A float target does not take an integer
                // literal — that would be a promotion, and there is none.
                val expected = if (targetType.isInteger) targetType else null
                val valueType = checkExpr(expr.value, expected)
                if (!sameType(valueType, targetType)) {
                    throw SemaException("Attempt reassign $targetType $valueType")
                }
            }

            ":=", ".=", "&=", "|=" -> {
                if (!targetType.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $targetType")
                }
                val valueType = checkExpr(expr.value, null)
                if (!valueType.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $valueType")
                }
                val literal = maskLiteral(expr.value)
                if (literal != null && !fitsWidth(literal, targetType)) {
                    throw SemaException("Value out of range")
                }
            }

            else -> throw SemaException("Unknown assignment operator ${expr.op}")
        }
    }

    private fun checkCall(expr: Call, expected: WolinType?): WolinType {
        val callee = expr.callee
        if (callee is MemberAccess) {
            val receiverType = checkExpr(callee.receiver, null, allowStruct = true)
            if (receiverType.kind == TypeKind.TRAIT) {
                val trait = traitTypes[receiverType.traitName]
                    ?: throw SemaException("Unknown trait ${receiverType.traitName}")
                val slot = trait.methods.indexOfFirst { it.name == callee.name }
                if (slot < 0) {
                    throw SemaException("Trait ${trait.name} has no method ${callee.name}")
                }
                val method = trait.methods[slot]
                checkCallArgs(expr, callee.name, method.params)
                expr.traitSlot = slot
                expr.type = method.retType ?: Types.unit
                return expr.type
            }
            val owner = structDeclOf(receiverType)
                ?: throw SemaException("Unsupported construct: member call ${callee.name}")
            val binding = methodsByStruct[owner]?.get(callee.name)
                ?: throw SemaException("Struct ${owner.name} has no method ${callee.name}")
            checkCallArgs(expr, callee.name, binding.decl.params)
            expr.resolvedMethod = binding.decl
            expr.methodPrefix = binding.prefix
            expr.type = binding.decl.retType ?: Types.unit
            return expr.type
        }
        if (callee !is VarRef) {
            throw SemaException("Unsupported call target")
        }
        val symbol = lookup(callee.name)
            ?: throw SemaException("Unknown function ${callee.name}")
        when (symbol) {
            is Symbol.StructSymbol ->
                throw SemaException("Struct ${symbol.decl.name} is a type, not a function")
            is Symbol.EnumSymbol ->
                throw SemaException("Enum ${symbol.decl.name} is a type, not a function")
            is Symbol.FunSymbol -> {
                resolved[callee] = symbol
                callee.type = functionTypeOf(symbol.decl)
                checkCallArgs(expr, callee.name, symbol.decl.params)
                expr.type = symbol.decl.retType ?: Types.unit
                return expr.type
            }
            // A `uword` holds a 16-bit code address; calling through it is an
            // indirect call. The signature is not known here, so the result
            // type is inferred from the expected type, and the arguments are
            // checked only for well-formedness.
            is Symbol.DataSymbol -> {
                // A function-pointer-typed variable is called through the
                // pointer: its signature is known, so the arguments are
                // checked against it.
                if (symbol.type.kind == TypeKind.FUNCTION) {
                    resolved[callee] = symbol
                    callee.type = symbol.type
                    val params = symbol.type.params
                    if (params.size != expr.args.size) {
                        throw SemaException(
                            "Function ${callee.name} expects ${params.size} arguments " +
                                "but got ${expr.args.size}"
                        )
                    }
                    expr.args.forEachIndexed { index, argument ->
                        val argumentType = checkExpr(argument, params[index])
                        if (!sameType(argumentType, params[index])) {
                            throw SemaException(
                                "Attempt reassign ${params[index]} $argumentType"
                            )
                        }
                    }
                    expr.type = symbol.type.retType ?: Types.unit
                    return expr.type
                }
                if (symbol.type.kind != TypeKind.UWORD) {
                    throw SemaException("${callee.name} not function")
                }
                resolved[callee] = symbol
                callee.type = symbol.type
                expr.args.forEach { checkExpr(it, null) }
                expr.type = expected ?: Types.ubyte
                return expr.type
            }
        }
    }

    private fun checkCallArgs(expr: Call, name: String, params: List<Param>) {
        if (params.size != expr.args.size) {
            throw SemaException(
                "Function $name expects ${params.size} arguments but got ${expr.args.size}"
            )
        }
        expr.args.forEachIndexed { index, argument ->
            val argumentType = checkExpr(argument, params[index].type)
            if (!sameType(argumentType, params[index].type)) {
                throw SemaException("Attempt reassign ${params[index].type} $argumentType")
            }
        }
    }

    private fun checkFunctionLiteral(expr: FunctionLit, expected: WolinType?): WolinType {
        // The parameter types come from the expected type at the literal's
        // site; without one there is nothing to infer them from.
        val paramTypes = FunctionLiteralInfer.infer(expr, expected)
        expr.paramTypes = paramTypes
        val retType = expected?.retType ?: Types.unit
        val outer = scope
        val literalScope = scope.child()
        scope = literalScope
        val outerLabels = currentLabels
        currentLabels = collectLabels(expr.body)
        val outerThis = currentThis
        currentThis = null
        val outerReturnType = currentReturnType
        try {
            expr.params.forEachIndexed { index, name ->
                literalScope.declare(name, Symbol.DataSymbol(paramTypes[index], null, null))
            }
            currentReturnType = retType
            expr.body.forEach { checkStmt(it) }
            // The literal's value is its last expression, so that expression
            // must have the expected return type.
            val last = expr.body.lastOrNull()
            if (last is ExprStmt && retType.kind != TypeKind.UNIT) {
                val valueType = last.expr.type
                if (!sameType(valueType, retType)) {
                    throw SemaException("Attempt reassign $retType $valueType")
                }
            }
        } finally {
            scope = outer
            currentLabels = outerLabels
            currentThis = outerThis
            currentReturnType = outerReturnType
        }
        expr.type = WolinType(
            TypeKind.FUNCTION,
            params = paramTypes,
            retType = retType
        )
        return expr.type
    }

    private fun checkIfExpr(expr: IfExpr, expected: WolinType?): WolinType {
        requireBool(checkExpr(expr.cond, null), "if")
        val thenType = checkBranch(expr.then, expected)
        val elseType = checkBranch(expr.otherwise, expected)
        expr.type = if (sameType(thenType, elseType)) thenType else Types.unit
        return expr.type
    }

    private fun checkArrayAccess(expr: ArrayAccess): WolinType {
        val arrayType = checkExpr(expr.array, null)
        val elementType = when (arrayType.kind) {
            TypeKind.ARRAY -> arrayType.elementType ?: Types.unit
            // A string is an array of bytes; indexing it yields a `ubyte`.
            TypeKind.STRING -> Types.ubyte
            else -> throw SemaException("Cannot index a value of type $arrayType")
        }
        val indexType = checkExpr(expr.index, null)
        if (!indexType.isInteger) {
            throw SemaException("Array index must be an integer, not $indexType")
        }
        expr.type = elementType
        return expr.type
    }

    private fun checkMemberAccess(expr: MemberAccess): WolinType {
        val receiver = expr.receiver
        if (receiver is VarRef) {
            val symbol = lookup(receiver.name)
            if (symbol is Symbol.EnumSymbol) {
                val entry = symbol.decl.entries.firstOrNull { it.name == expr.name }
                    ?: throw SemaException("Unknown enum entry ${expr.name} in ${symbol.decl.name}")
                expr.enumValue = entry.value
                expr.type = Types.ubyte
                return expr.type
            }
        }
        val receiverType = checkExpr(expr.receiver, null, allowStruct = true)
        val owner = structDeclOf(receiverType)
            ?: throw SemaException("Cannot access member ${expr.name} of $receiverType")
        val field = owner.fields.firstOrNull { it.name == expr.name }
            ?: throw SemaException("Unknown field ${expr.name} in ${owner.name}")
        expr.owner = owner
        expr.type = field.type
        return expr.type
    }

    /**
     * An explicit conversion: the operand must be an integer, the result is the
     * named target type. This is the only way a value changes integer type —
     * there is no promotion anywhere else.
     */
    private fun checkConvert(expr: Convert): WolinType {
        val valueType = checkExpr(expr.value, null)
        if (!valueType.isInteger) {
            throw SemaException("${expr.to} conversion requires an integer, not $valueType")
        }
        expr.type = expr.to
        return expr.type
    }

    // ---- type rules -------------------------------------------------------

    /**
     * Checks an explicit array count against the index type and the address
     * space. Domain-sized arrays keep their historical sizing (and are not
     * capped here); an explicit count is a promise the compiler can check.
     */
    private fun validateArrayType(type: WolinType, location: Location?, name: String, line: Int?) {
        if (type.kind != TypeKind.ARRAY) return
        val element = type.elementType ?: throw SemaException("Array $name has no element type", line)
        // A domain-sized scalar array keeps its historical sizing. A struct
        // or trait array is checked even without an explicit count: its
        // elements are larger than one byte.
        if (type.count == null &&
            element.kind != TypeKind.STRUCT && element.kind != TypeKind.TRAIT
        ) return
        val indexType = type.indexType ?: Types.ubyte
        val length = arrayLength(type)
        if (length < 1) throw SemaException("Array length must be at least 1", line)
        val domain = arrayDomain(indexType)
        if (length > domain) {
            throw SemaException(
                "Array length $length exceeds the $indexType index range $domain",
                line,
            )
        }
        val bytes = length.toLong() * typeSize(element)
        if (bytes > 65536) {
            throw SemaException("Array $name does not fit in the 64 KiB address space", line)
        }
        if (location is FixedArray && location.address.toLong() + bytes > 65536) {
            throw SemaException("Array $name does not fit in the 64 KiB address space", line)
        }
    }

    /** The size in bytes of a storage type (a scalar or a struct). */
    private fun typeSize(type: WolinType): Int = when (type.kind) {
        TypeKind.STRUCT -> structDeclOf(type)?.size ?: 0
        TypeKind.TRAIT -> 4
        TypeKind.ARRAY -> arrayLength(type) * typeSize(type.elementType ?: Types.ubyte)
        else -> sizeOf(type)
    }

    /** The index type's domain: 256 for `ubyte`/`byte`, 65536 for `uword`/`word`. */
    private fun arrayDomain(indexType: WolinType): Int =
        if (indexType.kind == TypeKind.UWORD || indexType.kind == TypeKind.WORD) 65536 else 256

    /**
     * The type of an integer literal: the expected type when the context
     * supplies one, the smallest type that holds the value otherwise.
     *
     * [allowWidth] admits the value as a bit pattern of the expected type's
     * width, so a signed `word` accepts `0xFF00` as a mask.
     */
    private fun literalType(
        value: Long,
        expected: WolinType?,
        allowWidth: Boolean = false,
    ): WolinType {
        if (expected != null && (expected.isInteger || expected.kind == TypeKind.FLOAT)) {
            val fitsExpected = if (allowWidth) fitsWidth(value, expected) else fits(value, expected)
            if (expected.isInteger && !fitsExpected) {
                throw SemaException("Value out of range")
            }
            return expected
        }
        return smallestType(value)
    }

    /** Types an [IntLit] against its operand; [allowWidth] is for bit masks. */
    private fun checkContextualIntLiteral(
        expr: IntLit,
        expected: WolinType,
        allowWidth: Boolean,
    ): WolinType = literalType(expr.value, expected, allowWidth).also { expr.type = it }

    /** The smallest type that holds `value`. */
    private fun smallestType(value: Long): WolinType = when {
        value in 0..255 -> Types.ubyte
        value in 256..65535 -> Types.uword
        value in -128..-1 -> Types.byte
        value in -32768..-129 -> Types.word
        else -> throw SemaException("Value out of range")
    }

    /** Whether `value` fits into `type`. */
    private fun fits(value: Long, type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE -> value in 0..255
        TypeKind.BYTE -> value in -128..127
        TypeKind.UWORD -> value in 0..65535
        TypeKind.WORD -> value in -32768..32767
        else -> false
    }

    /**
     * Whether [value] fits into the *width* of [type]. A bit mask is a bit
     * pattern, so `0xFF` fits an `ubyte` even though it does not fit a
     * `byte`.
     */
    private fun fitsWidth(value: Long, type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE -> value in 0..255
        TypeKind.BYTE -> value in -128..255
        TypeKind.UWORD -> value in 0..65535
        TypeKind.WORD -> value in -32768..65535
        else -> false
    }

    /**
     * The literal value of a bit mask, or null when the mask is not a literal.
     *
     * A negated literal is a `UnaryOp`, not an `IntLit`, so the width check
     * has to see through the negation. The value to check is the literal's own
     * value, not its negation: `-1` is the bit pattern `0xFF`, whose magnitude
     * `1` fits an `ubyte`.
     */
    private fun maskLiteral(value: Expr): Long? = when (value) {
        is IntLit -> value.value
        is UnaryOp -> if (value.op == "-") (value.operand as? IntLit)?.value else null
        else -> null
    }

    private fun requireBool(type: WolinType, what: String) {
        if (type.kind != TypeKind.BOOL) {
            throw SemaException("$what requires a bool condition, not $type")
        }
    }

    private fun requireNumeric(type: WolinType, op: String) {
        if (!type.isInteger && type.kind != TypeKind.FLOAT) {
            throw SemaException("Operator $op is not defined for $type")
        }
    }

    private fun functionTypeOf(decl: FunDecl): WolinType = WolinType(
        TypeKind.FUNCTION,
        params = decl.params.map { it.type },
        retType = decl.retType ?: Types.unit
    )
}
