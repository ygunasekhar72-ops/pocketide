package com.pocketide.mobile.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
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

    /**
     * M2: the editor placeholder is replaced by the real Monaco surface
     * (pinned 0.57.0 engine loaded from APK assets). Robolectric cannot run
     * the Chromium engine inside the WebView, so the on-screen assertions
     * cover the app-owned controls and honest status text; the WebView's
     * internal rendering is verified on real hardware (M2 device criteria).
     */
    @Test
    fun editorScreen_rendersRealMonacoSurfaceWithoutSimulation() {
        val viewModel = com.pocketide.mobile.editor.EditorViewModel(
            com.pocketide.mobile.editor.MonacoEditorStore(tmp.root)
        )
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.editor.EditorScreen(
                    projectName = "kotlin-playground",
                    viewModel = viewModel
                )
            }
        }
        composeRule.onNodeWithText("kotlin-playground").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onAllNodesWithText("Esc").assertCountEquals(1)
        composeRule.onAllNodesWithText("Tab").assertCountEquals(1)
        composeRule
            .onNodeWithText("Monaco · M2 · files · M3")
            .assertDoesNotExist()
        composeRule
            .onNodeWithText("in-app store · real files M3")
            .assertIsDisplayed()
        // The per-project load lands on an IO thread; wait for the honest
        // ready-state footer instead of racing it.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText("Engine: Monaco 0.57.0 · document: kotlin-playground")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithText("Engine: Monaco 0.57.0 · document: kotlin-playground")
            .assertIsDisplayed()
    }

    /**
     * M2: the terminal placeholder is replaced by the real PTY-driven
     * screen: not-started state renders honest controls (start enabled,
     * stop/clear disabled), nothing fabricated runs until the user starts.
     */
    @Test
    fun terminalScreen_rendersRealEngineControlsWithoutFabricatedOutput() {
        val viewModel = com.pocketide.mobile.terminal.TerminalViewModel(
            workspaceRoot = tmp.root,
            newHandle = { throw IllegalStateException("no session should start here") }
        )
        composeRule.setContent {
            PocketTheme {
                com.pocketide.mobile.terminal.TerminalScreen(
                    projectName = null,
                    viewModel = viewModel
                )
            }
        }
        composeRule.onNodeWithText("session").assertIsDisplayed()
        composeRule.onNodeWithText("workspace").assertIsDisplayed()
        composeRule.onNodeWithText("No terminal session").assertIsDisplayed()
        composeRule.onNodeWithText("press ▶ to start a session").assertIsDisplayed()
        composeRule.onNodeWithText("Command input (start a session)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("New session").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Stop session").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Clear output").assertIsNotEnabled()
        composeRule.onAllNodesWithText("native PTY").assertCountEquals(1)
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
        composeRule.onNodeWithText("License").performScrollTo().assertIsDisplayed()
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
