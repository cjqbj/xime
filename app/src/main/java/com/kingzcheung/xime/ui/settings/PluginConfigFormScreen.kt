package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import com.kingzcheung.xime.plugin.core.config.PluginSettingField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginConfigFormScreen(
    pluginId: String,
    plugin: IPluginConfigurable,
    pluginName: String,
    onBack: () -> Unit,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val configStore = remember(pluginId) {
        PluginConfigStoreImpl(context.applicationContext as android.app.Application, pluginId)
    }
    val fields = plugin.getSettingsSchema()
    val groupedFields = remember(fields) { fields.groupBy { it.section.orEmpty() } }

    val dynamicOptions = remember(plugin) { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(plugin, fields) {
        fields.filter { it.type == PluginFieldType.SELECT || it.type == PluginFieldType.MULTI_SELECT }
            .forEach { field ->
                val opts = withContext(Dispatchers.IO) {
                    runCatching { plugin.getOptions(field.key) }.getOrNull()
                }
                if (opts != null) dynamicOptions[field.key] = opts
            }
    }

    @Composable
    fun sectionContent(section: String, sectionFields: List<PluginSettingField>) {
        SettingsSection(
            title = section.ifBlank { "插件配置" },
            content = {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sectionFields.forEach { field ->
                        PluginSettingFieldEditor(
                            field = field,
                            configStore = configStore,
                            options = field.options.ifEmpty {
                                dynamicOptions[field.key].orEmpty()
                            },
                            plugin = plugin
                        )
                    }
                }
            }
        )
    }

    @Composable
    fun saveButton() {
        Button(
            onClick = {
                var allValid = true
                fields.forEach { field ->
                    if (field.type == PluginFieldType.SECRET && field.required) {
                        val current = configStore.get(field.key)
                        if (current.isNullOrBlank()) allValid = false
                    }
                }
                if (!allValid) {
                    Toast.makeText(context, "请填写必填配置", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                if (!embedded) onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("保存")
        }
    }

    if (embedded) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            groupedFields.forEach { (section, sectionFields) ->
                sectionContent(section, sectionFields)
            }
            saveButton()
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(pluginName) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
            ) {
                groupedFields.forEach { (section, sectionFields) ->
                    item {
                        sectionContent(section, sectionFields)
                    }
                }

                item {
                    saveButton()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PluginSettingFieldEditor(
    field: PluginSettingField,
    configStore: PluginConfigStore,
    options: List<String>,
    plugin: IPluginConfigurable
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var buttonBusy by remember(field.key) { mutableStateOf(false) }
    var value by rememberSaveable(field.key) {
        mutableStateOf(configStore.get(field.key) ?: field.defaultValue ?: "")
    }
    var showSecret by rememberSaveable(field.key) { mutableStateOf(false) }
    var expanded by rememberSaveable(field.key) { mutableStateOf(false) }

    fun persist() {
        configStore.set(field.key, value)
    }

    when (field.type) {
        PluginFieldType.TEXT,
        PluginFieldType.NUMBER -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label) },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = if (field.type == PluginFieldType.NUMBER) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        PluginFieldType.SECRET -> {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    persist()
                },
                label = { Text(field.label) },
                placeholder = field.placeholder?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (showSecret) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecret) "隐藏" else "显示"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = field.helpText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
        }

        PluginFieldType.SWITCH -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    field.helpText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = value == "true",
                    onCheckedChange = {
                        value = it.toString()
                        persist()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }

        PluginFieldType.SELECT -> {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    supportingText = if (options.isEmpty() && field.options.isEmpty())
                        { { Text("暂无可用选项") } }
                    else
                        field.helpText?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                value = option
                                persist()
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        PluginFieldType.MULTI_SELECT -> {
            val selected = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                field.helpText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (options.isEmpty()) {
                    Text(
                        text = "暂无可用选项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = option in selected,
                                onClick = {
                                    val next = if (option in selected) {
                                        selected - option
                                    } else {
                                        selected + option
                                    }
                                    value = next.sorted().joinToString(",")
                                    persist()
                                },
                                label = { Text(option) }
                            )
                        }
                    }
                }
            }
        }

        PluginFieldType.BUTTON -> {
            Button(
                onClick = {
                    val action = field.action
                    if (action.isNullOrBlank()) {
                        Toast.makeText(context, "未配置动作", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    buttonBusy = true
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            runCatching { plugin.onAction(action) }.getOrNull()
                        }
                        buttonBusy = false
                        if (error.isNullOrBlank()) {
                            Toast.makeText(context, "成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !buttonBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(if (buttonBusy) "处理中…" else field.label)
            }
        }
    }
}
