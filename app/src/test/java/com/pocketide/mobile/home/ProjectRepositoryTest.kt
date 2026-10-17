package com.pocketide.mobile.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketide.mobile.home.data.ProjectRepository
import java.io.File

/**
 * JVM tests against a temp directory — the same pure file operations the
 * app uses at runtime (no Android APIs involved below the repository).
 */
class ProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(): ProjectRepository =
        ProjectRepository(tmp.root)

    @Test
    fun emptyRoot_listsNoProjects() {
        assertTrue(repository().listProjects().isEmpty())
    }

    @Test
    fun create_thenList_returnsProjectWithMetadata() {
        val repo = repository()
        val result = repo.createProject("alpha")

        assertTrue(result is ProjectRepository.CreateResult.Created)
        val projects = repo.listProjects()
        assertEquals(1, projects.size)
        assertEquals("alpha", projects[0].name)
        assertEquals(0, projects[0].itemCount)
        assertTrue(projects[0].path.endsWith("alpha"))
    }

    @Test
    fun nestedFiles_countAsItems() {
        val repo = repository()
        repo.createProject("demo")
        File(tmp.root, "demo/src").mkdirs()
        File(tmp.root, "demo/main.kt").writeText("") // placeholder marker file, not app data

        assertEquals(2, repo.listProjects()[0].itemCount)
    }

    @Test
    fun duplicateName_isRejected() {
        val repo = repository()
        repo.createProject("dupe")
        assertEquals(ProjectRepository.CreateResult.AlreadyExists, repo.createProject("dupe"))
    }

    @Test
    fun invalidNames_areRejectedWithoutSideEffects() {
        val repo = repository()
        listOf("", "  ", ".hidden", "a/b", "a\\b", "..", ".dot", "a b", "very_long_" + "x".repeat(70))
            .forEach { bad ->
                assertEquals("rejected: '$bad'", ProjectRepository.CreateResult.InvalidName, repo.createProject(bad))
            }
        assertTrue(repo.listProjects().isEmpty())
        assertEquals(0, tmp.root.listFiles()?.size)
    }

    @Test
    fun traversalSurvival_checkRootIsolation() {
        val repo = repository()
        // Even a crafted valid name must stay inside root; pattern denies separators.
        repo.createProject("proj.sub-dir_1")
        assertTrue(File(tmp.root, "proj.sub-dir_1").isDirectory)
    }

    @Test
    fun delete_removesOnlyTargetProject() {
        val repo = repository()
        repo.createProject("keep")
        repo.createProject("gone")

        assertEquals(ProjectRepository.DeleteResult.Deleted, repo.deleteProject("gone"))
        assertFalse(File(tmp.root, "gone").exists())
        assertTrue(File(tmp.root, "keep").isDirectory)
        assertEquals(listOf("keep"), repo.listProjects().map { it.name })
    }

    @Test
    fun delete_missingOrInvalid_isRejected() {
        val repo = repository()
        assertEquals(ProjectRepository.DeleteResult.NotFound, repo.deleteProject("ghost"))
        assertEquals(ProjectRepository.DeleteResult.InvalidName, repo.deleteProject("x/y"))
    }

    @Test
    fun projects_listSortedMostRecentFirst() {
        val repo = repository()
        repo.createProject("old")
        // Force a deterministic, distinct modification time (avoid clock granularity).
        File(tmp.root, "old").setLastModified(0L)
        repo.createProject("new")

        assertEquals(listOf("new", "old"), repo.listProjects().map { it.name })
    }
}
