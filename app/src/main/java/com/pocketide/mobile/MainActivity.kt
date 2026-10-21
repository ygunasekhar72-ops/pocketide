package com.pocketide.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketide.mobile.assistant.AssistantScreen
import com.pocketide.mobile.editor.EditorScreen
import com.pocketide.mobile.editor.EditorViewModel
import com.pocketide.mobile.editor.MonacoEditorStore
import com.pocketide.mobile.home.HomeScreen
import com.pocketide.mobile.home.HomeViewModel
import com.pocketide.mobile.home.data.ProjectRepository
import com.pocketide.mobile.home.data.Workspace
import com.pocketide.mobile.settings.SettingsScreen
import com.pocketide.mobile.terminal.PtyLinuxHandle
import com.pocketide.mobile.terminal.TerminalScreen
import com.pocketide.mobile.terminal.TerminalViewModel
import com.pocketide.mobile.ui.navigation.NavigationState
import com.pocketide.mobile.ui.navigation.PocketDestination
import com.pocketide.mobile.ui.shell.PocketBottomNav
import com.pocketide.mobile.ui.shell.PocketTopBar
import com.pocketide.mobile.ui.theme.PocketTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketTheme {
                PocketIDEApp()
            }
        }
    }
}

@Composable
private fun PocketIDEApp() {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(Workspace.root(context)) }

    val navigation = remember { NavigationState() }
    val stack by navigation.stack.collectAsState()
    val current = stack.last()

    var selectedProject by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deliverMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val homeViewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    )

    val editorViewModel: EditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    MonacoEditorStore(
                        File(context.filesDir, "editor-docs")
                    )
                )
            }
        }
    )

    val terminalViewModel: TerminalViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TerminalViewModel(
                    workspaceRoot = repository.root,
                    newHandle = { PtyLinuxHandle() }
                )
            }
        }
    )

    var homeQuery by rememberSaveable { mutableStateOf("") }

    // Detail screens (pushed) pop on system back; tab roots let the system exit.
    BackHandler(enabled = navigation.canGoBack) { navigation.pop() }

    Scaffold(
        topBar = {
            PocketTopBar(
                destination = current,
                contextProject = selectedProject.takeIf { current != PocketDestination.Home }
            )
        },
        bottomBar = {
            PocketBottomNav(
                current = current,
                onSelect = { tab -> navigation.switchTo(tab) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(data) } }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when (current) {
                PocketDestination.Home -> HomeScreen(
                    state = homeViewModel.collectState(),
                    query = homeQuery,
                    filteredProjects = homeViewModel.filtered(homeQuery),
                    onCreateProject = { name, onResult -> homeViewModel.createProject(name, onResult) },
                    onDeleteProject = { name, onResult -> homeViewModel.deleteProject(name, onResult) },
                    onQueryChange = { homeQuery = it },
                    onOpenProject = { name ->
                        selectedProject = name
                        navigation.push(PocketDestination.Editor)
                    },
                    onMessage = deliverMessage,
                    onRetry = { homeViewModel.refresh() }
                )
                PocketDestination.Editor -> EditorScreen(
                    projectName = selectedProject,
                    viewModel = editorViewModel
                )
                PocketDestination.Terminal -> TerminalScreen(
                    projectName = selectedProject,
                    viewModel = terminalViewModel
                )
                PocketDestination.Assistant -> AssistantScreen(
                    projectName = selectedProject,
                    onNavigateToSettings = { navigation.switchTo(PocketDestination.Settings) }
                )
                PocketDestination.Settings -> SettingsScreen(
                    workspacePath = repository.root.absolutePath,
                    onMessage = deliverMessage
                )
            }
        }
    }
}

/** Bridges HomeViewModel's StateFlow into Compose state. */
@Composable
private fun HomeViewModel.collectState() =
    state.collectAsState().value
