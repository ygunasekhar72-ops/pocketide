package com.pocketide.mobile.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.AppIdentity
import com.pocketide.mobile.home.data.ProjectRepository
import com.pocketide.mobile.ui.components.ConfirmDialog
import com.pocketide.mobile.ui.components.EmptyState
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.components.MonoIcon
import com.pocketide.mobile.ui.components.SectionHeader
import com.pocketide.mobile.ui.theme.PocketMonoFont
import java.text.DateFormat
import java.util.Date

/**
 * Welcome/workspace screen.
 *
 * [HomeContent] is a pure function of [HomeUiState] so it can be tested in
 * isolation; the ViewModel-bound wrapper below is thin glue.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    query: String,
    filteredProjects: List<ProjectRepository.ProjectInfo>,
    onCreateProject: (name: String, onResult: (ProjectActionResult) -> Unit) -> Unit,
    onDeleteProject: (name: String, onResult: (ProjectActionResult) -> Unit) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenProject: (name: String) -> Unit,
    onMessage: (String) -> Unit,
    onRetry: () -> Unit
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        when (state) {
            is HomeUiState.Loading -> LoadingState()
            is HomeUiState.Error -> ErrorState(state, onRetry)
            is HomeUiState.Ready -> HomeContent(
                state = state,
                query = query,
                filteredProjects = filteredProjects,
                onCreateClick = { showCreateDialog = true },
                onOpenProject = onOpenProject,
                onQueryChange = onQueryChange,
                onDeleteClick = { pendingDelete = it }
            )
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                onCreateProject(name) { result ->
                    onMessage(
                        when (result) {
                            is ProjectActionResult.Success -> result.message
                            is ProjectActionResult.Failure -> result.message
                        }
                    )
                }
            }
        )
    }

    pendingDelete?.let { name ->
        ConfirmDialog(
            title = "Delete project",
            body = "\"$name\" and all of its files will be removed from device storage. This cannot be undone.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                pendingDelete = null
                onDeleteProject(name) { result ->
                    onMessage(
                        when (result) {
                            is ProjectActionResult.Success -> result.message
                            is ProjectActionResult.Failure -> result.message
                        }
                    )
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(state: HomeUiState.Error, onRetry: () -> Unit) {
    EmptyState(
        title = "Workspace unreadable",
        description = state.message,
        symbol = "!!",
        action = {
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState.Ready,
    query: String,
    filteredProjects: List<ProjectRepository.ProjectInfo>,
    onCreateClick: () -> Unit,
    onOpenProject: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onDeleteClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoIcon(symbol = "PD", modifier = Modifier.size(36.dp))
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(
                            text = AppIdentity.APP_NAME,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Build on your phone, for real.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Work in progress — editor engine (M2), terminal (M1), Git (M5) and AI (M6) arrive step by step. Creating projects is fully functional local storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCreateClick) { Text("Create project") }
                    OutlinedButton(
                        onClick = {},
                        enabled = false
                    ) { Text("Open folder…") }
                    MilestoneBadge("M3")
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filter projects") },
                singleLine = true
            )
        }

        item {
            SectionHeader(
                title = "Recent projects",
                trailing = {
                    Text(
                        text = "${filteredProjects.size}/${state.projects.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        if (filteredProjects.isEmpty() && state.projects.isEmpty()) {
            item {
                EmptyState(
                    title = "No projects yet",
                    description = "Create your first project to start coding on device.",
                    symbol = "[+]",
                    action = { Button(onClick = onCreateClick) { Text("Create project") } }
                )
            }
        } else if (filteredProjects.isEmpty()) {
            item {
                EmptyState(
                    title = "No match",
                    description = "No project name matches \"$query\".",
                    symbol = "?"
                )
            }
        } else {
            items(filteredProjects, key = { it.name }) { project ->
                ProjectCard(
                    project = project,
                    onClick = { onOpenProject(project.name) },
                    onDelete = { onDeleteClick(project.name) }
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.workspacePath,
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectRepository.ProjectInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoIcon(symbol = "[P]", modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    fontFamily = PocketMonoFont,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${project.itemCount} item(s) · modified ${formatDate(project.lastModifiedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete ${project.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMillis))
