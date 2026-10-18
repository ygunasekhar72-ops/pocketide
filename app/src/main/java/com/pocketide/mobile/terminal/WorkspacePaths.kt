package com.pocketide.mobile.terminal

import com.pocketide.mobile.home.data.ProjectRepository
import java.io.File

/**
 * Resolves the terminal working directory inside the app-private workspace.
 *
 * Hard rules:
 *  - the session never escapes [Workspace] root: the project name is validated
 *    with the same public pattern the projects UI and repository use
 *    ([ProjectRepository.NAME_PATTERN]), so traversal like "../x" or "a/b"
 *    can never reach the native `chdir` in a later phase;
 *  - a valid but non-existent/removed project falls back to the workspace
 *    root instead of failing the session start.
 */
object WorkspacePaths {

    fun resolveWorkingDirectory(workspaceRoot: File, projectName: String?): File {
        if (projectName == null) return workspaceRoot
        if (!ProjectRepository.NAME_PATTERN.matches(projectName)) return workspaceRoot
        val dir = File(workspaceRoot, projectName)
        return if (dir.isDirectory) dir else workspaceRoot
    }
}
