package com.pocketide.mobile.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketide.mobile.home.HomeUiState
import com.pocketide.mobile.home.data.ProjectRepository
import com.pocketide.mobile.home.data.Workspace
import com.pocketide.mobile.ui.navigation.NavigationState
import com.pocketide.mobile.ui.navigation.PocketDestination
import com.pocketide.mobile.ui.shell.PocketBottomNav
import com.pocketide.mobile.ui.theme.PocketTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric-based Compose tests: shell navigation taps and the real
 * filesystem-backed Home screen states.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PocketAppShellTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: ProjectRepository

    @Before
    fun setUpWorkspace() {
        repository = ProjectRepository(File(tmp.root, Workspace.DIR_NAME))
    }

    @Test
    fun bottomNav_showsAllFiveTabs_andSwitchesScreen() {
        val state = NavigationState()
        composeRule.setContent {
            PocketTheme {
                PocketBottomNav(
                    current = state.current,
                    onSelect = { state.switchTo(it) }
                )
            }
        }

        PocketDestination.bottomTabs.forEach { tab ->
            composeRule.onNodeWithText(tab.label).assertIsDisplayed()
        }

        composeRule.onNodeWithText("Terminal").performClick()
        assertEquals(PocketDestination.Terminal, state.current)

        composeRule.onNodeWithText("Settings").performClick()
        assertEquals(PocketDestination.Settings, state.current)
    }

    @Test
    fun editorPlaceholder_rendersEnginelessWithMilestoneBadges() {
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.editor.EditorContent(projectName = "kotlin-playground")
            }
        }
        composeRule.onNodeWithText("kotlin-playground").assertIsDisplayed()
        composeRule.onNodeWithText("No file open").assertIsDisplayed()
        composeRule.onNodeWithText("Monaco · M2 · files · M3").assertIsDisplayed()
    }

    @Test
    fun terminalPlaceholder_rendersWithoutFabricatedOutput() {
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.terminal.TerminalContent(projectName = null)
            }
        }
        composeRule.onNodeWithText("session 0").assertIsDisplayed()
        composeRule.onNodeWithText("No terminal session").assertIsDisplayed()
        composeRule.onAllNodesWithText("engine · M1").assertCountEquals(1)
    }

    @Test
    fun settingsContent_showsRealIdentity() {
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.settings.SettingsContent(
                    workspacePath = "/data/user/0/com.pocketide.mobile/files/workspace",
                    onMessage = {}
                )
            }
        }
        composeRule.onNodeWithText("com.pocketide.mobile").assertIsDisplayed()
        composeRule.onNodeWithText("/data/user/0/com.pocketide.mobile/files/workspace").assertIsDisplayed()
        composeRule.onNodeWithText("License").assertIsDisplayed()
    }

    @Test
    fun assistantPlaceholder_noFakeConversation() {
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.assistant.AssistantScreen(
                    projectName = null,
                    onNavigateToSettings = {}
                )
            }
        }
        composeRule.onNodeWithText("Provider: not configured").assertIsDisplayed()
        composeRule.onNodeWithText("Configure").performClick() // hook must exist, no crash
    }

    @Test
    fun homeContent_showsRealWorkspaceProjects() {
        repository.createProject("alpha")

        val ready = HomeUiState.Ready(
            projects = repository.listProjects(),
            workspacePath = repository.root.absolutePath
        )
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.home.HomeContent(
                    state = ready,
                    query = "",
                    filteredProjects = ready.projects,
                    onCreateClick = {},
                    onOpenProject = {},
                    onQueryChange = {},
                    onDeleteClick = {}
                )
            }
        }
        composeRule.onNodeWithText("alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Recent projects").assertIsDisplayed()
    }

    @Test
    fun homeContent_emptyState_showsCreateCta() {
        val empty = HomeUiState.Ready(
            projects = emptyList(),
            workspacePath = repository.root.absolutePath
        )
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.home.HomeContent(
                    state = empty,
                    query = "",
                    filteredProjects = emptyList(),
                    onCreateClick = {},
                    onOpenProject = {},
                    onQueryChange = {},
                    onDeleteClick = {}
                )
            }
        }
        composeRule.onNodeWithText("No projects yet").assertIsDisplayed()
        composeRule.onAllNodesWithText("Create project").assertCountEquals(2)
    }
}
