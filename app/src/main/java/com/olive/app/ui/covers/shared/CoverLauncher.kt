package com.olive.app.ui.covers.shared

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.olive.app.data.CoverType
import com.olive.app.data.AccountRole

// Android reads launcher names/icons from aliases; switch them here when the saved cover changes.
object CoverLauncher {
    // Keep Android Studio's Run target alive without touching the user's visible cover icon.
    fun ensureStudioLauncher(context: Context) {
        val manager=context.packageManager
        val component=ComponentName(context.packageName,"${context.packageName}.OliveLauncher")
        if(manager.getComponentEnabledSetting(component)!=PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(component,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
    }

    fun apply(context: Context, selected: CoverType, role: AccountRole) {
        val manager = context.packageManager
        val aliases=listOf("FoodLauncher","NewsLauncher","CalculatorLauncher","BranchLauncher","TreeLauncher")
        val target=when(role) {
            AccountRole.SAFE_CONTACT -> "BranchLauncher"
            AccountRole.STAFF,AccountRole.ADMIN -> "TreeLauncher"
            AccountRole.USER -> when(selected) {
                CoverType.NEWS -> "NewsLauncher"
                CoverType.CALCULATOR -> "CalculatorLauncher"
                else -> "FoodLauncher"
            }
        }
        val changes=aliases.map { name ->
            val component=ComponentName(context.packageName,"${context.packageName}.$name")
            val state=if(name==target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            component to state
        }.filter { (component,state) -> manager.getComponentEnabledSetting(component)!=state }
        if(changes.isEmpty()) return
        // On Pixel's Android versions, change the aliases together so a launcher always stays enabled.
        if(Build.VERSION.SDK_INT>=33) {
            manager.setComponentEnabledSettings(changes.map { (component,state) ->
                PackageManager.ComponentEnabledSetting(component,state,PackageManager.DONT_KILL_APP)
            })
        } else {
            changes.sortedByDescending { it.second==PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component,state) -> manager.setComponentEnabledSetting(component,state,PackageManager.DONT_KILL_APP) }
        }
    }
}
