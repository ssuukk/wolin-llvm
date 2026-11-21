package pl.qus.wolin.sema

/**
 * Direct Unicode code point → PETSCII byte mapping.
 * Case inversion is applied: Unicode 'A' → 0xC1, Unicode 'a' → 0x41.
 */
object PetsciiConverter {

    /**
     * Returns the PETSCII byte for a Unicode code point, or null if unmapped.
     */
    fun unicodeToPetscii(codePoint: Int): Int? = UNICODE_TO_PETSCII[codePoint]

    private val UNICODE_TO_PETSCII: Map<Int, Int> = buildMap {
        // Printable ASCII punctuation, digits, and symbols (0x20–0x40 map directly)
        for (cp in 0x20..0x40) put(cp, cp)

        // Uppercase letters (A–Z) → PETSCII high range (0xC1–0xDA)
        for (i in 0..25) put(0x41 + i, 0xC1 + i)

        // Lowercase letters (a–z) → PETSCII low range (0x41–0x5A)
        for (i in 0..25) put(0x61 + i, 0x41 + i)

        // Special punctuation / symbols
        put(0x00A3, 0x5C)   // £
        put(0x03C0, 0xFF)   // π
        put(0x2190, 0x5F)   // ←
        put(0x2191, 0x5E)   // ↑

        // Box-drawing and block characters
        put(0x2500, 0xC0)   // ─
        put(0x2502, 0xDD)   // │
        put(0x250C, 0xB0)   // ┌
        put(0x2510, 0xAE)   // ┐
        put(0x2514, 0xAD)   // └
        put(0x2518, 0xBD)   // ┘
        put(0x251C, 0xAB)   // ├
        put(0x2524, 0xB3)   // ┤
        put(0x252C, 0xB2)   // ┬
        put(0x2534, 0xB1)   // ┴
        put(0x253C, 0xDB)   // ┼
        put(0x256D, 0xD5)   // ╭
        put(0x256E, 0xC9)   // ╮
        put(0x256F, 0xCB)   // ╯
        put(0x2570, 0xCA)   // ╰
        put(0x2571, 0xCE)   // ╱
        put(0x2572, 0xCD)   // ╲
        put(0x2573, 0xD6)   // ╳
        put(0x2581, 0xA4)   // ▁
        put(0x2582, 0xAF)   // ▂
        put(0x2583, 0xB9)   // ▃
        put(0x2584, 0xA2)   // ▄
        put(0x258C, 0xA1)   // ▌
        put(0x258D, 0xB5)   // ▍
        put(0x258E, 0xB4)   // ▎
        put(0x258F, 0xA5)   // ▏
        put(0x2592, 0xA6)   // ▒
        put(0x2594, 0xA3)   // ▔
        put(0x2595, 0xA7)   // ▕
        put(0x2596, 0xBB)   // ▖
        put(0x2597, 0xAC)   // ▗
        put(0x2598, 0xBE)   // ▘
        put(0x259A, 0xBF)   // ▚
        put(0x259D, 0xBC)   // ▝
        put(0x25CB, 0xD7)   // ○
        put(0x25CF, 0xD1)   // ●
        put(0x25E4, 0xA9)   // ◤
        put(0x25E5, 0xDF)   // ◥

        // Card suits and check mark
        put(0x2660, 0xC1)   // ♠
        put(0x2663, 0xD8)   // ♣
        put(0x2665, 0xD3)   // ♥
        put(0x2666, 0xDA)   // ♦
        put(0x2713, 0xBA)   // ✓

        // Unicode 13+ fractional block elements (U+1FBxx)
        put(0x1FB70, 0xD4)
        put(0x1FB71, 0xC7)
        put(0x1FB72, 0xC2)
        put(0x1FB73, 0xDD)
        put(0x1FB74, 0xC8)
        put(0x1FB75, 0xD9)
        put(0x1FB76, 0xC5)
        put(0x1FB77, 0xC4)
        put(0x1FB78, 0xC3)
        put(0x1FB79, 0xC0)
        put(0x1FB7A, 0xC6)
        put(0x1FB7B, 0xD2)
        put(0x1FB7C, 0xCC)
        put(0x1FB7D, 0xCF)
        put(0x1FB7E, 0xD0)
        put(0x1FB7F, 0xBA)
        put(0x1FB82, 0xB7)
        put(0x1FB83, 0xB8)
        put(0x1FB87, 0xAA)
        put(0x1FB88, 0xB6)
        put(0x1FB8C, 0xDC)
        put(0x1FB8F, 0xA8)
        put(0x1FB95, 0xFF)
        put(0x1FB98, 0xDF)
        put(0x1FB99, 0xA9)
    }

    /**
     * The PETSCII byte for a byte produced by string decoding, falling back to
     * the byte itself when the character is not part of the PETSCII set.
     */
    fun byteToPetscii(byte: Byte): Byte = PetsciiConverter.unicodeToPetscii(byte.toInt() and 0xFF)?.toByte()
        ?: byte

    /**
     * The PETSCII byte for a Unicode code point, falling back to the low byte
     * of the code point when the character is not part of the PETSCII set.
     *
     * The fallback keeps a character that PETSCII has no slot for visible as
     * something on screen instead of dropping it silently.
     */
    fun codePointToPetscii(codePoint: Int): Byte =
        unicodeToPetscii(codePoint)?.toByte() ?: (codePoint and 0xFF).toByte()
}
