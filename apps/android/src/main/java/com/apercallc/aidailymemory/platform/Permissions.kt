package com.apercallc.aidailymemory.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.apercallc.aidailymemory.recording.permission.PermissionSnapshot

/**
 * Snapshots runtime permissions for [com.apercallc.aidailymemory.recording.permission.PermissionFlow].
 * "Requested before" is persisted because Android cannot tell a first request from a
 * permanent denial on its own.
 */
class Permissions(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    val microphoneGranted: Boolean get() = isGranted(Manifest.permission.RECORD_AUDIO)

    val notificationsGranted: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)

    fun microphone(activity: Activity): PermissionSnapshot =
        snapshot(activity, Manifest.permission.RECORD_AUDIO)

    fun notifications(activity: Activity): PermissionSnapshot =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PermissionSnapshot.NotRequired
        } else {
            snapshot(activity, Manifest.permission.POST_NOTIFICATIONS)
        }

    fun markRequested(permission: String) {
        prefs.edit { putBoolean(permission, true) }
    }

    private fun snapshot(activity: Activity, permission: String) = PermissionSnapshot(
        granted = isGranted(permission),
        shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission),
        requestedBefore = prefs.getBoolean(permission, false),
    )

    companion object {
        private const val PREFS = "permission_requests"

        val NOTIFICATIONS: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }
    }
}
