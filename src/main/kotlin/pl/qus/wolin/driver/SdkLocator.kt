package pl.qus.wolin.driver

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The llvm-mos SDK: the directory holding `bin/mos-c64-clang`. */
data class Sdk(val root: Path) {
    val clang: Path get() = root.resolve("bin/mos-c64-clang")
}

/**
 * Locates the llvm-mos SDK.
 *
 * Resolution order:
 * 1. [explicit] — the `--sdk` option. Returned as-is, no existence check: a
 *    bad explicit path surfaces when the toolchain runs.
 * 2. The `WOLIN_SDK` environment variable (from [env]). Returned as-is, no
 *    existence check.
 * 3. The `sdk-*` glob over each directory in [globRoots], newest first (sort
 *    by version descending, comparing the numeric suffix); the first
 *    directory that exists wins.
 * 4. `mos-c64-clang` on `PATH` (read from [env], split on `:`; skipped when
 *    [env] has no `PATH` entry). The SDK root is the parent of the
 *    executable's parent: `<root>/bin/mos-c64-clang` → `<root>`.
 *
 * When nothing matches, throws [IllegalStateException] naming every step
 * tried and every path checked.
 */
object SdkLocator {
    private const val ENV_VAR = "WOLIN_SDK"
    private const val CLANG = "mos-c64-clang"
    private const val GLOB_PREFIX = "sdk-"

    fun locate(
        explicit: String? = null,
        env: Map<String, String> = System.getenv(),
        globRoots: List<Path> = emptyList(),
    ): Sdk {
        if (explicit != null) return Sdk(Path.of(explicit))
        env[ENV_VAR]?.let { return Sdk(Path.of(it)) }
        for (root in globRoots) {
            val match = sdkCandidates(root).firstOrNull()
            if (match != null) return Sdk(match)
        }
        val exe = env["PATH"]?.split(':')?.map { Path.of(it, CLANG) }
            ?.firstOrNull { Files.isRegularFile(it) }
        if (exe != null) return Sdk(exe.parent.parent)
        throw IllegalStateException(notFoundMessage(globRoots, env.containsKey("PATH")))
    }

    private fun notFoundMessage(globRoots: List<Path>, pathSet: Boolean): String =
        "Wolin SDK not found. Tried: " +
            "the --sdk option (none given); " +
            "the $ENV_VAR environment variable (not set); " +
            (if (globRoots.isEmpty()) {
                "the sdk-* glob (no glob roots configured)"
            } else {
                "the sdk-* glob under " + globRoots.joinToString(", ") { it.toString() } + " (no match)"
            }) +
            "; " +
            (if (pathSet) {
                "mos-c64-clang on PATH (not found)"
            } else {
                "mos-c64-clang on PATH (PATH not set)"
            }) +
            "."

    /** The `sdk-*` directories under [root], newest first. */
    private fun sdkCandidates(root: Path): List<Path> {
        val files = root.toFile().listFiles { f -> f.isDirectory && f.name.startsWith(GLOB_PREFIX) }
        if (files == null) return emptyList()
        val dirs: List<Path> = files.map { f: File -> f.toPath() }
        return dirs.sortedWith(Comparator { a, b -> compareVersions(a, b) })
    }

    /** Numeric version comparison: `v23.2.0` vs `v3.0`, group by group. */
    private fun compareVersions(a: Path, b: Path): Int {
        val va = versionKey(a.fileName.toString().removePrefix(GLOB_PREFIX))
        val vb = versionKey(b.fileName.toString().removePrefix(GLOB_PREFIX))
        for (i in 0 until minOf(va.size, vb.size)) {
            val c = va[i].compareTo(vb[i])
            if (c != 0) return c
        }
        return va.size - vb.size
    }

    /** The numeric suffix of an `sdk-*` version, e.g. `v23.2.0` → [23, 2, 0]. */
    private fun versionKey(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
}
