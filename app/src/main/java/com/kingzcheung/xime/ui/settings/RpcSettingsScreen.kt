package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.RpcSettings
import com.kingzcheung.xime.settings.RpcSettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpcSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { RpcSettingsStore.load(context) }
    var httpEnabled by remember { mutableStateOf(initial.httpEnabled) }
    var httpPort by remember { mutableStateOf(initial.httpPort.toString()) }
    var httpHost by remember { mutableStateOf(initial.httpHost) }
    var httpKey by remember { mutableStateOf(initial.httpKey) }
    var mqttEnabled by remember { mutableStateOf(initial.mqttEnabled) }
    var requestTopic by remember { mutableStateOf(initial.mqttRequestTopic) }
    var responseTopic by remember { mutableStateOf(initial.mqttResponseTopic) }
    var pubKey by remember { mutableStateOf(initial.mqttPubKey) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RPC 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("HTTP RPC", style = MaterialTheme.typography.titleMedium)
            RpcSwitchRow("启用 HTTP RPC", httpEnabled) { httpEnabled = it }
            RpcField("HTTP 监听地址", httpHost, { httpHost = it }, "例如 0.0.0.0")
            RpcField(
                "HTTP 端口",
                httpPort,
                { httpPort = it.filter(Char::isDigit) },
                "1024 - 65535",
                KeyboardType.Number
            )
            RpcField("HTTP 路径前缀", httpKey, { httpKey = it }, "留空表示不使用")

            Text("MQTT RPC", style = MaterialTheme.typography.titleMedium)
            RpcSwitchRow("启用 MQTT RPC", mqttEnabled) { mqttEnabled = it }
            RpcField("请求 Topic", requestTopic, { requestTopic = it }, "默认 sys/device/request")
            RpcField("回复 Topic", responseTopic, { responseTopic = it }, "默认 sys/device/response")
            RpcField(
                "MQTT 公钥",
                pubKey,
                { pubKey = it },
                "输入框清空代表不使用公钥验签"
            )

            OutlinedButton(
                onClick = {
                    val port = httpPort.toIntOrNull()
                    message = if (port == null || port !in 1..65535) {
                        "HTTP 端口无效"
                    } else {
                        val saved = RpcSettingsStore.save(
                            RpcSettings(
                                httpEnabled = httpEnabled,
                                httpPort = port,
                                httpHost = httpHost.trim().ifEmpty { "0.0.0.0" },
                                httpKey = httpKey,
                                mqttEnabled = mqttEnabled,
                                mqttRequestTopic = requestTopic,
                                mqttResponseTopic = responseTopic,
                                mqttPubKey = pubKey
                            )
                        )
                        if (saved) "已保存，重启应用后生效" else "保存失败，请检查存储权限"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.TwoTone.Build, contentDescription = null)
                Text("保存 RPC 配置")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text(
                "配置文件：${RpcSettingsStore.CONFIG_PATH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RpcSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, modifier = Modifier.padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RpcField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        minLines = if (label == "MQTT 公钥") 3 else 1
    )
}
