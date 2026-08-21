package com.hpremote.clone.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required so Android considers this app eligible for the default-SMS role
 * (see AndroidManifest.xml) - handles the Phone app's "quick response" action.
 * Not implemented since this app has no messaging UI; it just stops itself.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
