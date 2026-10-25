package com.cuong.cflip.control

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings

/** Quick controls: flashlight, volume, brightness, and system panels. */
object QuickControls {

    // ---- Flashlight (no camera permission needed for the torch) ----
    @Volatile var torchOn: Boolean = false
        private set

    // getCameraCharacteristics() throws CameraAccessException (camera in use,
    // disconnected) - that must turn into "no torch", never a crash in the UI.
    private fun torchCameraId(cm: CameraManager): String? = runCatching {
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    fun setTorch(context: Context, on: Boolean): Boolean {
        val cm = context.getSystemService(CameraManager::class.java) ?: return false
        val id = torchCameraId(cm) ?: return false
        return runCatching { cm.setTorchMode(id, on); torchOn = on; true }.getOrDefault(false)
    }

    // ---- Volume ----
    fun volume(context: Context): Pair<Int, Int> {
        val am = context.getSystemService(AudioManager::class.java) ?: return 0 to 1
        return runCatching {
            am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0 to 1)
    }

    fun setVolume(context: Context, value: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // 3.2.0: flag 0, like 9Flip's ve.java setStreamVolume(3, v, 0).
        // FLAG_SHOW_UI used to pop Samsung's own volume panel on the
        // main display over the cover — that was the "Samsung sound bar"
        // the user saw. CFlip's own slider is the bar now, no popup.
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
    }

    // ---- Brightness (needs "Modify system settings" grant for writing) ----
    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun brightness(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(125)

    fun setBrightness(context: Context, value: Int): Boolean {
        if (!canWriteSettings(context)) return false
        // Switch to manual mode so the value actually applies
        runCatching {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value); true
        }.getOrDefault(false)
    }

    // ---- Panels & settings shortcuts (the supported way to "toggle" system features) ----
    fun openWifiPanel(context: Context) {
        context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openInternetPanel(context: Context) {
        context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openBluetoothSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openNfcPanel(context: Context) {
        context.startActivity(Intent(Settings.Panel.ACTION_NFC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openDeviceSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openWriteSettingsPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
    fun openUsageAccessSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
