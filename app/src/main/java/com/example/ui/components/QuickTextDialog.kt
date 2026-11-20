package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun QuickTextDialog(
    onAdd: (fileName: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fileName by rememberSaveable { mutableStateOf("shared_note.txt") }
    var textBody by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_text_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(stringResource(R.string.quick_text_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_text_name_input"),
                )
                OutlinedTextField(
                    value = textBody,
                    onValueChange = { textBody = it },
                    label = { Text(stringResource(R.string.quick_text_body_label)) },
                    placeholder = { Text("Paste a link, code snippet, or note...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp)
                        .testTag("quick_text_body_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (textBody.isNotBlank()) {
                        onAdd(fileName, textBody)
                        onDismiss()
                    }
                },
                enabled = textBody.isNotBlank(),
                modifier = Modifier.testTag("quick_text_add_button"),
            ) {
                Text(stringResource(R.string.quick_text_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
