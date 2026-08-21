package com.hpremote.clone.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Required so Android considers this app eligible for the default-SMS role
 * (see AndroidManifest.xml). Incoming MMS during the brief window this app
 * holds the role aren't stored - out of scope for a one-time data-migration
 * tool - but the receiver must exist for the role grant to be allowed.
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally no-op.
    }
}
