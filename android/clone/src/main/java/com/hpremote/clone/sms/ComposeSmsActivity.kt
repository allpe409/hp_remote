package com.hpremote.clone.sms

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Required so Android considers this app eligible for the default-SMS role
 * (see AndroidManifest.xml) - the role is only ever taken transiently to
 * import old messages, so this app doesn't actually implement composing.
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "hp_remote 폰 복제는 문자 작성을 지원하지 않습니다", Toast.LENGTH_SHORT).show()
        finish()
    }
}
