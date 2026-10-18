package com.pocketide.mobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File get() = tmp.root

    @Test
    fun nullProject_resolvesToWorkspaceRoot() {
        assertEquals(root, WorkspacePaths.resolveWorkingDirectory(root, null))
    }

    @Test
    fun validExistingProject_resolvesToProjectDirectory() {
        val dir = tmp.newFolder("alpha")
        assertEquals(dir, WorkspacePaths.resolveWorkingDirectory(root, "alpha"))
    }

    @Test
    fun validButMissingProject_fallsBackToWorkspaceRoot() {
        assertEquals(root, WorkspacePaths.resolveWorkingDirectory(root, "ghost"))
    }

    @Test
    fun traversalAttempts_neverEscapeWorkspace() {
        tmp.newFolder("alpha")
        for (bad in listOf("..", "../<escape>", "a/b", "a\\b", ".", "hidden")) {
            assertEquals("pattern '$bad' must not escape root", root,
                WorkspacePaths.resolveWorkingDirectory(root, bad))
        }
    }

    @Test
    fun projectDirReplacedByFile_fallsBackToWorkspaceRoot() {
        val file = tmp.newFile("notadir")
        assertEquals(root, WorkspacePaths.resolveWorkingDirectory(root, file.name))
    }

    @Test
    fun nameAtPatternMaxLength_acceptedForExistingProject() {
        // NAME_PATTERN total length max is 65 (1 leading alnum + <=64 more).
        // An EXISTING directory makes acceptance provable: a name the pattern
        // rejected could only resolve to the root, never to the project dir.
        val name = "a".repeat(65)
        val dir = tmp.newFolder(name)
        assertEquals(dir, WorkspacePaths.resolveWorkingDirectory(root, name))
    }

    @Test
    fun nameAtPatternMaxLength_missingDirectoryFallsBackToRoot() {
        // Same valid 65-char name, directory absent: the missing-dir fallback
        // (not the rejection branch) must yield the workspace root.
        assertEquals(root, WorkspacePaths.resolveWorkingDirectory(root, "a".repeat(65)))
    }

    @Test
    fun nameOverPatternMaxLength_rejectedEvenWhenDirectoryExists() {
        // 66 chars exceeds the pattern. The directory EXISTS, so resolving to
        // the root can only come from the invalid-name rejection branch; the
        // missing-dir fallback cannot produce this outcome.
        val name = "a".repeat(66)
        tmp.newFolder(name)
        assertEquals(root, WorkspacePaths.resolveWorkingDirectory(root, name))
    }
}
