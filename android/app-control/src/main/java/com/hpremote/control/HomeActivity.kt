package com.hpremote.control

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hpremote.control.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRemote.setOnClickListener {
            startActivity(Intent(this, com.hpremote.agent.MainActivity::class.java))
        }
        binding.btnClone.setOnClickListener {
            startActivity(Intent(this, com.hpremote.clone.MainActivity::class.java))
        }
    }
}
