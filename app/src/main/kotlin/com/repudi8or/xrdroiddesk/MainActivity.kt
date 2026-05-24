package com.repudi8or.xrdroiddesk

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.repudi8or.xrdroiddesk.service.GestureAccessibilityService

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvServiceStatus)

        findViewById<Button>(R.id.btnDebugClick).setOnClickListener {
            val service = GestureAccessibilityService.instance
            if (service != null) {
                service.triggerDebugClick(x = 500f, y = 500f)
                tvStatus.setText(R.string.service_status_click_sent)
            } else {
                tvStatus.setText(R.string.service_status_disconnected)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val connected = GestureAccessibilityService.instance != null
        tvStatus.setText(
            if (connected) {
                R.string.service_status_connected
            } else {
                R.string.service_status_disconnected
            },
        )
    }
}
