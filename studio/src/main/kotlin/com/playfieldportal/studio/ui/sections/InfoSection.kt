package com.playfieldportal.studio.ui.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.StudioViewModel
import com.playfieldportal.themekit.MANIFEST_DESCRIPTION_MAX
import com.playfieldportal.themekit.PfpThemeSource

/** Where the theme came from, as one line. Pure. */
fun originLine(state: StudioState): String {
    val source = state.source
    if (source != null && source.type == PfpThemeSource.TYPE_PTF_IMPORT) {
        return buildString {
            append("Imported from ${source.file ?: "a PSP theme"}")
            source.firmware?.let { append(" — firmware $it") }
        }
    }
    return state.created?.let { "Created $it" } ?: "New theme — not saved yet"
}

@Composable
fun InfoSection(state: StudioState, viewModel: StudioViewModel) {
    SectionColumn {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Theme name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.author.orEmpty(),
            onValueChange = viewModel::setAuthor,
            label = { Text("Author") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.description.orEmpty(),
            onValueChange = viewModel::setDescription,
            label = { Text("Short description") },
            supportingText = { Text("${state.description?.length ?: 0} / $MANIFEST_DESCRIPTION_MAX") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        MutedText(originLine(state))
        MutedText("Saved: name, author and description travel inside the .pfptheme. The updated date is set when you export.", 11)
    }
}
