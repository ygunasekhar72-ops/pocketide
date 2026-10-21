package com.pocketide.mobile.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Monaco bundle provenance pin (decision 0002, M2 credential of record).
 * The asset files must byte-match the pinned official monaco-editor
 * 0.57.0 release tarball; the harness must be the project's own file with
 * the documented bridge surface. Any drift (a manual edit inside the
 * vendored bundle, a wrong version, a different file) fails this test.
 *
 * Plain JVM test: assets are read from the module directory.
 */
class MonacoPinnedProvenanceTest {

    private fun assetsDir(): File {
        val working = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(working, "app/src/main/assets/monaco"),
            File(working, "src/main/assets/monaco")
        )
        val found = candidates.firstOrNull { it.isDirectory }
        if (found == null) throw IllegalStateException(
            "Monaco assets directory not found under any candidate root (tests must run from the module or repo root)"
        )
        return found
    }

    private fun sha256hex(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { b -> "%02x".format(b) }

    @Test
    fun `pinned metadata declares the exact monaco release`() {
        val provenance = File(assetsDir(), "monaco-pinned.json").readText()
        assertEquals(
            true,
            provenance.contains("\"version\": \"0.57.0\"")
        )
        assertEquals(
            true,
            provenance.contains("https://registry.npmjs.org/monaco-editor/-/monaco-editor-0.57.0.tgz")
        )
        assertEquals(
            true,
            provenance.contains("3ea1712fbacd3290cf4751007e3a5b57cc279767607812d4c3e57925fb0b05c2")
        )
    }

    @Test
    fun `bundled key files byte-match the pinned digests`() {
        val pin = MonacoJson.parse(
            File(assetsDir(), "monaco-pinned.json").readText()
        ) as? MonacoJson.Json.Obj
        assertTrue("pinned metadata must be an object", pin != null)
        @Suppress("UNCHECKED_CAST")
        val extra = pin!!.entries["keyFileSha256"] as? MonacoJson.Json.Obj
        assertTrue(extra != null)
        for ((relativeKey, declaredRaw) in extra!!.entries) {
            val declared = (declaredRaw as? MonacoJson.Json.Str)?.value ?: continue
            val actual = sha256hex(File(assetsDir(), relativeKey))
            assertEquals("pinned bytes drifted for $relativeKey", declared, actual)
        }
    }

    @Test
    fun `harness identity and bridge name stay in lockstep`() {
        val harness = File(assetsDir(), "index.html").readText()
        // The harness must load the pinned engine bundle and speak to the
        // same bridge object name the Kotlin side injects.
        assertTrue(harness.contains("src=\"vs/loader.js\""))
        assertTrue(harness.contains(MONACO_BRIDGE_NAME))
        assertTrue(harness.contains("window.pocketIDE"))
        assertTrue(harness.contains("applyWindowEdit"))
        assertTrue(harness.contains("reportContent"))
        assertTrue(harness.contains("reportWindow"))
    }

    @Test
    fun `license file is the bundled Monaco MIT license`() {
        val license = File(assetsDir(), "LICENSE").readText()
        assertTrue(license.contains("Copyright (c) 2016 - present Microsoft Corporation"))
    }
}
