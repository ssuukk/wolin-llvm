package pl.qus.wolin.driver

import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * The symbol map that ties a Wolin external function to the symbol a
 * linked archive defines.
 *
 * Each line is `<wolin symbol> = <rust symbol>`. The Wolin symbol is the
 * one the compiler emits — the mangled name of the external function —
 * and the Rust symbol is the one the archive exports. The map becomes a
 * linker alias: the Wolin symbol resolves to the Rust one.
 *
 * ```
 * t.add = wol_add
 * ```
 *
 * The Rust library needs no knowledge of Wolin's naming: the mapping
 * lives here, in the build.
 */
class RustMap(val entries: List<Pair<String, String>>) {

    /**
     * The linker arguments that apply the map: one `--defsym` per entry.
     *
     * The linker takes the alias first and the target second, and the
     * target must be a symbol the archive actually defines.
     */
    fun defsymArguments(): List<String> =
        entries.map { (wolin, rust) -> "--defsym=$wolin=$rust" }

    companion object {
        /**
         * Reads the map from [file].
         *
         * A line is a comment when it starts with `#`, and a blank line is
         * ignored. Every other line must name exactly one pair; a line
         * that does not is an error naming the line.
         */
        fun parse(file: Path): RustMap {
            val entries = mutableListOf<Pair<String, String>>()
            file.readLines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val parts = line.split('=')
                if (parts.size != 2) {
                    throw IllegalArgumentException(
                        "${file.fileName}:${index + 1}: expected '<wolin symbol> = <rust symbol>', got: $line"
                    )
                }
                val wolin = parts[0].trim()
                val rust = parts[1].trim()
                if (wolin.isEmpty() || rust.isEmpty()) {
                    throw IllegalArgumentException(
                        "${file.fileName}:${index + 1}: expected '<wolin symbol> = <rust symbol>', got: $line"
                    )
                }
                entries += wolin to rust
            }
            val duplicates = entries.groupBy { it.first }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                throw IllegalArgumentException(
                    "${file.fileName}: duplicate symbol ${duplicates.first()}"
                )
            }
            return RustMap(entries)
        }
    }
}
