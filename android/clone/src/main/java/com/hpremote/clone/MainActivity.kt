package com.hpremote.clone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.clone.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSend.setOnClickListener { startActivity(Intent(this, SendActivity::class.java)) }
        binding.btnReceive.setOnClickListener { startActivity(Intent(this, ReceiveActivity::class.java)) }
    }
}
