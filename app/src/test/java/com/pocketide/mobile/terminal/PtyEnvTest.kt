package com.pocketide.mobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM tests for the P3a child-environment builder. No JNI, no Android
 * runtime: the nativePTY itself needs an Android device (P3e).
 */
class PtyEnvTest {

    private val cwd = File("/data/data/com.pocketide.mobile/files/ws/project-a")

    private fun spec(env: Map<String, String> = emptyMap()) = PtySpec(cwd = cwd, env = env)

    @Test
    fun `base environment exposes PATH TERM and HOME`() {
        val vars = PtyEnv.build(spec()).joinToString("\n")
        assertTrue(vars.contains("PATH=/system/bin:/system/xbin"))
        assertTrue(vars.contains("TERM=xterm-256color"))
        assertTrue(vars.contains("HOME=${cwd.absolutePath}"))
    }

    @Test
    fun `overlay replaces base entries by exact key`() {
        val vars = PtyEnv.build(spec(mapOf("TERM" to "dumb"))).joinToString("\n")
        assertTrue(vars.contains("TERM=dumb"))
        assertTrue(!vars.contains("TERM=xterm-256color"))
        assertEquals(1, vars.split("\n").count { it.startsWith("TERM=") })
    }

    @Test
    fun `overlay appends extra entries`() {
        val vars = PtyEnv.build(spec(mapOf("POCKET_POC" to "1"))).joinToString("\n")
        assertTrue(vars.contains("POCKET_POC=1"))
        assertTrue(vars.contains("PATH=/system/bin:/system/xbin"))
    }

    @Test
    fun `empty overlay yields exactly the three base entries`() {
        val vars = PtyEnv.build(spec())
        assertEquals(3, vars.size)
        assertEquals(
            setOf(
                "PATH=/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "HOME=${cwd.absolutePath}"
            ),
            vars.toSet()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid key with space throws`() {
        PtyEnv.build(spec(mapOf("BAD KEY" to "x")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid key starting with digit throws`() {
        PtyEnv.build(spec(mapOf("0KEY" to "x")))
    }

    @Test
    fun `every entry is a KEY=VALUE pair with a legal key`() {
        val vars = PtyEnv.build(spec(mapOf("A" to "b", "SHELLish" to "x")))
        assertEquals(4, vars.size)
        val keyRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
        assertTrue(vars.all { keyRegex.containsMatchIn(it) })
    }
}
