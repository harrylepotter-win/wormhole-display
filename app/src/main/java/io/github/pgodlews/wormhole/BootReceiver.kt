package io.github.pgodlews.wormhole

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver triggered when the Android system boots up or after an app update.
 * Automatically starts [WormholeService] in the background if autostart is enabled.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val startOnBoot = prefs.getBoolean("start_on_boot", true)
            val runInBackground = prefs.getBoolean("run_in_background", true)

            Log.i(TAG, "Checking autostart settings: start_on_boot=$startOnBoot, run_in_background=$runInBackground")
            if (startOnBoot && runInBackground) {
                Log.i(TAG, "Autostart condition met -> launching WormholeService")
                WormholeService.start(context)
            }
        }
    }
}
