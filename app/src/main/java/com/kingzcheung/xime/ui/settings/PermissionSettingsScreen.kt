package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** protectionLevel 低四位中，1 表示危险权限（运行时权限） */
private const val PROTECTION_DANGEROUS = 1

private data class RuntimePermission(
    val permission: String,
    val title: String,
    val description: String,
    val groupKey: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var refreshKey by remember { mutableStateOf(0) }

    val permissions = remember { findRuntimePermissions(context) }

    val grantedState = remember {
        mutableStateMapOf<String, Boolean>().apply {
            permissions.forEach { item ->
                put(item.permission, isGranted(context, item.permission))
            }
        }
    }

    val specialGranted = remember {
        mutableStateMapOf(
            "overlay" to Settings.canDrawOverlays(context),
            "write_settings" to Settings.System.canWrite(context),
            "battery" to isIgnoringBatteryOptimizations(context),
            "all_files" to isAllFilesAccessGranted(context),
            "install" to isInstallUnknownAppsGranted(context)
        )
    }

    // 顺序请求的状态机
    var currentPermission by remember { mutableStateOf<String?>(null) }
    var pendingPermissionQueue by remember { mutableStateOf<List<String>>(emptyList()) }
    var launchTrigger by remember { mutableStateOf(0) }
    var isRequesting by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        permissions.forEach { item ->
            grantedState[item.permission] = isGranted(context, item.permission)
        }
        specialGranted["overlay"] = Settings.canDrawOverlays(context)
        specialGranted["write_settings"] = Settings.System.canWrite(context)
        specialGranted["battery"] = isIgnoringBatteryOptimizations(context)
        specialGranted["all_files"] = isAllFilesAccessGranted(context)
        specialGranted["install"] = isInstallUnknownAppsGranted(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
        // 一个权限请求回调后，从队列取出下一个
        val queue = pendingPermissionQueue
        if (queue.isNotEmpty()) {
            currentPermission = queue.first()
            pendingPermissionQueue = queue.drop(1)
            launchTrigger++
        } else {
            currentPermission = null
            isRequesting = false
        }
    }

    // 由 launchTrigger 驱动实际 launch，避免在 launcher 回调里引用自身
    LaunchedEffect(launchTrigger) {
        if (launchTrigger > 0) {
            currentPermission?.let { permissionLauncher.launch(arrayOf(it)) }
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshKey++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val groupOfPermission = remember(permissions) {
        permissions.associate { it.permission to it.groupKey }
    }

    // 核心：顺序请求，一次只发一个权限
    fun launchPermissionRequest(candidates: List<String>) {
        val applicable = candidates
            .asSequence()
            .filter { it != Manifest.permission.ACCESS_BACKGROUND_LOCATION }
            .filter { isApplicable(it) && !isGranted(context, it) }
            .distinctBy { groupOfPermission[it] ?: permissionGroupKey(it) }
            .toList()

        if (applicable.isEmpty()) {
            refreshKey++
            return
        }

        if (isRequesting) return

        isRequesting = true
        currentPermission = applicable.first()
        pendingPermissionQueue = applicable.drop(1)
        launchTrigger++
    }

    fun launchSettings(action: String) {
        val primary = Intent(action, "package:${context.packageName}".toUri())
        try {
            settingsLauncher.launch(primary)
        } catch (e: ActivityNotFoundException) {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()
            )
            runCatching { settingsLauncher.launch(fallback) }
        }
    }

    fun onPermissionClicked(permission: String) {
        // 用户手动点击时取消正在进行的批量队列
        pendingPermissionQueue = emptyList()
        currentPermission = null
        isRequesting = false

        if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
            !isGranted(context, permission)
        ) {
            launchSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            return
        }
        launchPermissionRequest(listOf(permission))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "系统会按权限组依次弹窗；后台位置、悬浮窗等特殊权限需要进入系统设置单独授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = {
                    // 清掉旧队列再开始新的批量
                    pendingPermissionQueue = emptyList()
                    currentPermission = null
                    isRequesting = false
                    launchPermissionRequest(permissions.map { it.permission })
                }
            ) {
                Icon(Icons.TwoTone.Security, contentDescription = null)
                Text("申请全部可动态申请权限")
            }

            SettingsSection(title = "动态权限") {
                permissions.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    val granted = grantedState[item.permission] == true
                    val needsSystemSettings = !granted &&
                        item.permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION

                    SettingsItem(
                        icon = Icons.TwoTone.Security,
                        title = item.title,
                        subtitle = when {
                            granted -> "已授权 · ${item.description}"
                            needsSystemSettings -> "未授权 · 需前往系统设置授权"
                            else -> "未授权 · ${item.description}"
                        },
                        onClick = { onPermissionClicked(item.permission) },
                        showArrow = needsSystemSettings
                    )
                }
            }

            SettingsSection(title = "特殊权限") {
                SpecialPermissionItem(
                    title = "悬浮窗",
                    granted = specialGranted["overlay"] == true,
                    onClick = { launchSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
                )
                SpecialPermissionItem(
                    title = "修改系统设置",
                    granted = specialGranted["write_settings"] == true,
                    onClick = { launchSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS) }
                )
                SpecialPermissionItem(
                    title = "忽略电池优化",
                    granted = specialGranted["battery"] == true,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            launchSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        }
                    }
                )
                SpecialPermissionItem(
                    title = "所有文件访问",
                    granted = specialGranted["all_files"] == true,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            launchSettings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        }
                    }
                )
                SpecialPermissionItem(
                    title = "安装未知应用",
                    granted = specialGranted["install"] == true,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            launchSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SpecialPermissionItem(title: String, granted: Boolean, onClick: () -> Unit) {
    SettingsItem(
        icon = Icons.TwoTone.Security,
        title = title,
        subtitle = if (granted) "已授权" else "未授权 · 点击打开系统设置",
        onClick = onClick,
        showArrow = true
    )
}

/* ------------------------- 权限查询与判断 ------------------------- */

private fun findRuntimePermissions(context: Context): List<RuntimePermission> {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
    }.getOrNull() ?: return emptyList()

    return packageInfo.requestedPermissions.orEmpty().mapNotNull { permission ->
        val info = runCatching {
            context.packageManager.getPermissionInfo(permission, 0)
        }.getOrNull() ?: return@mapNotNull null

        if (info.protectionLevel and 0xF != PROTECTION_DANGEROUS) {
            return@mapNotNull null
        }
        if (!isApplicable(permission)) {
            return@mapNotNull null
        }

        RuntimePermission(
            permission = permission,
            title = permissionTitle(permission),
            description = permissionDescription(permission),
            groupKey = permissionGroupKey(permission)
        )
    }
}

private fun permissionTitle(permission: String): String = when (permission) {
    Manifest.permission.RECORD_AUDIO -> "麦克风"
    Manifest.permission.CAMERA -> "相机"
    Manifest.permission.ACCESS_FINE_LOCATION -> "精确位置"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "大致位置"
    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "后台位置"
    Manifest.permission.POST_NOTIFICATIONS -> "通知"
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE -> "附近设备"
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "照片和视频"
    Manifest.permission.READ_MEDIA_AUDIO -> "音乐和音频"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储空间"
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储空间"
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS -> "联系人"
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR -> "日历"
    Manifest.permission.READ_PHONE_STATE -> "电话状态"
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.SEND_SMS -> "短信"
    else -> permission.substringAfterLast('.').lowercase().replace('_', ' ')
}

private fun permissionDescription(permission: String): String = when (permission) {
    Manifest.permission.RECORD_AUDIO -> "录制音频"
    Manifest.permission.CAMERA -> "拍照和录制视频"
    Manifest.permission.ACCESS_FINE_LOCATION -> "通过 GPS 获取精确位置"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "通过网络获取大致位置"
    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "应用在后台时也能获取位置"
    Manifest.permission.POST_NOTIFICATIONS -> "发送通知"
    Manifest.permission.BLUETOOTH_SCAN -> "扫描附近蓝牙设备"
    Manifest.permission.BLUETOOTH_CONNECT -> "连接附近蓝牙设备"
    Manifest.permission.BLUETOOTH_ADVERTISE -> "向附近蓝牙设备广播"
    Manifest.permission.READ_MEDIA_IMAGES -> "读取图片"
    Manifest.permission.READ_MEDIA_VIDEO -> "读取视频"
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "读取用户选择的图片和视频"
    Manifest.permission.READ_MEDIA_AUDIO -> "读取音频文件"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "读取外部存储"
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入外部存储"
    Manifest.permission.READ_CONTACTS -> "读取联系人"
    Manifest.permission.WRITE_CONTACTS -> "修改联系人"
    Manifest.permission.READ_CALENDAR -> "读取日历"
    Manifest.permission.WRITE_CALENDAR -> "修改日历"
    Manifest.permission.READ_PHONE_STATE -> "读取设备信息"
    Manifest.permission.READ_SMS -> "读取短信"
    Manifest.permission.RECEIVE_SMS -> "接收短信"
    Manifest.permission.SEND_SMS -> "发送短信"
    else -> permission.substringAfterLast('.')
}

private fun permissionGroupKey(permission: String): String = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "location"

    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.GET_ACCOUNTS -> "contacts"

    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR -> "calendar"

    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.RECEIVE_MMS,
    Manifest.permission.RECEIVE_WAP_PUSH -> "sms"

    Manifest.permission.CAMERA -> "camera"
    Manifest.permission.RECORD_AUDIO -> "microphone"

    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.WRITE_CALL_LOG,
    Manifest.permission.ADD_VOICEMAIL,
    Manifest.permission.USE_SIP,
    Manifest.permission.PROCESS_OUTGOING_CALLS -> "phone"

    Manifest.permission.BODY_SENSORS -> "sensors"

    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE -> "storage"

    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "media_visual"

    Manifest.permission.READ_MEDIA_AUDIO -> "media_audio"

    Manifest.permission.POST_NOTIFICATIONS -> "notifications"

    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE -> "bluetooth"

    else -> permission
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isApplicable(permission: String): Boolean = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> false

    permission == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED ->
        Build.VERSION.SDK_INT >= 34

    permission == Manifest.permission.POST_NOTIFICATIONS ->
        Build.VERSION.SDK_INT >= 33

    permission.startsWith("android.permission.READ_MEDIA_") ->
        Build.VERSION.SDK_INT >= 33

    permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
        Build.VERSION.SDK_INT >= 29

    permission == Manifest.permission.BLUETOOTH_SCAN ||
        permission == Manifest.permission.BLUETOOTH_CONNECT ||
        permission == Manifest.permission.BLUETOOTH_ADVERTISE ->
        Build.VERSION.SDK_INT >= 31

    permission == Manifest.permission.READ_EXTERNAL_STORAGE ->
        Build.VERSION.SDK_INT <= 32

    permission == Manifest.permission.WRITE_EXTERNAL_STORAGE ->
        Build.VERSION.SDK_INT <= 29

    else -> true
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true)

private fun isAllFilesAccessGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        Environment.isExternalStorageManager()

private fun isInstallUnknownAppsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()