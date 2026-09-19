package com.kingzcheung.xime.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.kingzcheung.xime.settings.SettingsPreferences

object LauncherIconController {
    fun syncFromPreference(context: Context) {
        val hidden = SettingsPreferences.isLauncherIconHidden(context)
        setEnabled(context, !hidden)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context, "${context.packageName}.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, "${context.packageName}.LauncherAlias")
        return context.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
