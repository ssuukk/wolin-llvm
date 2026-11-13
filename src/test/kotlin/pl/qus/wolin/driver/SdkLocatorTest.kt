package pl.qus.wolin.driver

import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkLocatorTest {
    @Test fun `explicit path wins`() {
        val sdk = SdkLocator.locate("/opt/sdk", mapOf("WOLIN_SDK" to "/other"))
        assertEquals(Path.of("/opt/sdk"), sdk.root)
    }

    @Test fun `env var is used when no explicit path`() {
        val sdk = SdkLocator.locate(null, mapOf("WOLIN_SDK" to "/from/env"))
        assertEquals(Path.of("/from/env"), sdk.root)
    }

    @Test fun `missing sdk fails with a message naming what was tried`() {
        val e = assertThrows<IllegalStateException> {
            SdkLocator.locate(null, emptyMap(), globRoots = listOf(Path.of("/also/nope")))
        }
        assertTrue(e.message!!.contains("WOLIN_SDK"), e.message)
    }
}
