package com.kai.voicelock

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent

object LockController {

    @Volatile
    var accessibility: LockAccessibilityService? = null

    /**
     * Returns true if the screen was locked.
     * Preferred: accessibility GLOBAL_ACTION_LOCK_SCREEN (Android 9+, no admin needed).
     * Fallback: DevicePolicyManager.lockNow() via device admin.
     */
    fun lock(ctx: Context): Boolean {
        accessibility?.let { svc ->
            if (svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return true
        }
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            return true
        }
        return false
    }
}

class LockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        LockController.accessibility = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (LockController.accessibility === this) LockController.accessibility = null
        super.onDestroy()
    }
}
