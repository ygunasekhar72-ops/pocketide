package com.pocketide.mobile.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pocketide.mobile.home.data.ProjectRepository

/** Validates the name locally first, then calls back with the trimmed value. */
@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = ProjectRepository.NAME_PATTERN_PUBLIC.matches(name.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                Text(
                    "Creates a real folder in the workspace. Existing folders are never overwritten.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Project name") },
                    supportingText = {
                        Text(
                            text = when {
                                name.isBlank() -> HomeViewModel.INVALID_NAME_HINT
                                !valid -> "Not a valid project name"
                                else -> "OK"
                            },
                            color = if (!valid) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onCreate(name.trim()) }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
