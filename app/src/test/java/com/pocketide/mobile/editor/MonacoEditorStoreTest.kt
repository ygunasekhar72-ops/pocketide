package com.pocketide.mobile.editor

import com.pocketide.mobile.home.data.ProjectRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Persistence contract of the M2 editor document store — names, corrupt
 * payloads, oversized writes, and round-trip fidelity, using the SAME
 * project-name rule as the workspace projects UI.
 */
class MonacoEditorStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var store: MonacoEditorStore

    @Before
    fun setUp() {
        store = MonacoEditorStore(temporaryFolder.resolve("editor-docs"))
    }

    @Test
    fun `save then load round trips contents and language`() {
        store.save("alpha-project", MonacoDocument("the quick brown fox\n", "markdown"))
        val loaded = store.load("alpha-project")
        assertEquals("the quick brown fox\n", loaded?.content)
        assertEquals("markdown", loaded?.language)
    }

    @Test
    fun `load returns null for a project without a document`() {
        val loaded = store.load("empty-project")
        assertTrue(loaded == null)
    }

    @Test
    fun `save rejects names outside the projects pattern`() {
        for (name in listOf("../escape", "a/b", " evil", "", ".", "a\u0000b")) {
            try {
                store.save(name, MonacoDocument("x"))
                fail("expected rejection for '$name'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `store name rule agrees with the projects rule on a corpus`() {
        val corpus = listOf(
            "alpha-check", "alpha.doc", "12", "x_y-z.w", "a/b", "..x",
            "a".repeat(64), "a".repeat(65), " spaced", "trail ", ""
        )
        for (name in corpus) {
            val storeAccepted = runCatching {
                store.save(name, MonacoDocument("x"))
            }.isSuccess
            val projectsAccepted = ProjectRepository.NAME_PATTERN.matches(name)
            assertEquals(
                "the store must accept exactly the project-name pattern for '$name'",
                projectsAccepted,
                storeAccepted
            )
        }
    }

    @Test
    fun `oversized documents cannot be stored`() {
        val big = "x".repeat(MonacoEditorStore.MAX_DOCUMENT_CHARS + 1)
        try {
            store.save("big-doc", MonacoDocument(big))
            fail("expected oversized rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("store bound"))
        }
        // Nothing persisted.
        assertTrue(store.load("big-doc") == null)
    }

    @Test
    fun `corrupt stored files surface honestly instead of faking content`() {
        store.save("victim", MonacoDocument("fine"))
        val file = temporaryFolder.resolve("editor-docs").resolve("victim.json")
        file.writeText("{ not json ")
        try {
            store.load("victim")
            fail("expected corrupt-file failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("corrupt"))
        }
    }

    @Test
    fun `persisted shape stays within the one-object contract`() {
        store.save("shape", MonacoDocument("doc", "json"))
        val raw = temporaryFolder.resolve("editor-docs").resolve("shape.json").readText()
        val parsed = MonacoJson.parse(raw) as? MonacoJson.Json.Obj
        assertNotNull(parsed)
        assertEquals(
            setOf("content", "language"),
            parsed!!.entries.keys
        )
    }
}
