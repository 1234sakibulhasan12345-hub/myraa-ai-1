package com.example.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.TargetFramework
import com.example.ui.theme.NeonPurple

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, framework: TargetFramework, requirements: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var requirements by remember { mutableStateOf("") }
    var selectedFramework by remember { mutableStateOf(TargetFramework.ANDROID_COMPOSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    placeholder = { Text("e.g., Myraa Chat App") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_project_name_input")
                )

                Text(
                    text = "Target Stack",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TargetFramework.entries.forEach { fw ->
                        FilterChip(
                            selected = selectedFramework == fw,
                            onClick = { selectedFramework = fw },
                            label = { Text(fw.displayLabel) }
                        )
                    }
                }

                OutlinedTextField(
                    value = requirements,
                    onValueChange = { requirements = it },
                    label = { Text("Requirements (EN / বাংলা / Banglish)") },
                    placeholder = { Text("Describe features, database, auth & UI goals...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, selectedFramework, requirements) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                modifier = Modifier.testTag("dialog_create_project_confirm")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
