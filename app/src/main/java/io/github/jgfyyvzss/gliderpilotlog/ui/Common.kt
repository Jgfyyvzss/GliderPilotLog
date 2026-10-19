package io.github.jgfyyvzss.gliderpilotlog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import io.github.jgfyyvzss.gliderpilotlog.SoarlogsApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Dates use the device locale's short format. */
fun LocalDate.shortText(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))

fun isoDateShort(iso: String): String = runCatching { LocalDate.parse(iso).shortText() }.getOrDefault(iso)

/** The application object, for view model factories. */
val CreationExtras.app: SoarlogsApp get() = this[APPLICATION_KEY] as SoarlogsApp

@Composable
fun appContainer(): SoarlogsApp = LocalContext.current.applicationContext as SoarlogsApp

data class PickerOption(val id: String, val label: String)

/**
 * A read-only field that opens a list to pick from. Used in place of a dropdown menu because the glider and
 * airfield lists can be long.
 */
@Composable
fun PickerField(
    label: String,
    selectedId: String?,
    options: List<PickerOption>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    placeholder: String = "Choose",
    selectedLabel: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = selectedLabel ?: options.firstOrNull { it.id == selectedId }?.label ?: ""
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                if (options.isEmpty()) {
                    Text("Nothing to choose yet. Add one first.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(options, key = { it.id }) { option ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        option.label,
                                        style = if (option.id == selectedId) MaterialTheme.typography.titleMedium
                                        else MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onPick(option.id)
                                    open = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** A small label such as "Incomplete" or "Archived" shown on list rows. */
@Composable
fun Badge(text: String) {
    SuggestionChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) }, enabled = false)
}

/** Factory helper for view models that need the application container. */
inline fun <reified VM : ViewModel> vmFactory(crossinline create: (SoarlogsApp) -> VM) =
    androidx.lifecycle.viewmodel.viewModelFactory {
        addInitializer(VM::class) { create(app) }
    }
