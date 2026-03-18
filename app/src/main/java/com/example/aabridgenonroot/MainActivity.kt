package com.khanhtran.aamediabridgenonroot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnGrant = findViewById<Button>(R.id.btnGrantPermission)

        btnGrant.setOnClickListener {
            // Mở thẳng trang Cài đặt Quyền Đọc Thông Báo của Android
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnGrant = findViewById<Button>(R.id.btnGrantPermission)

        // Kiểm tra xem người dùng đã gạt công tắc cho app chưa
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        if (enabledListeners.contains(packageName)) {
            tvStatus.text = "Tuyệt vời!\nĐã cấp quyền thành công.\nBạn có thể kết nối Android Auto và sử dụng."
            btnGrant.isEnabled = false
            btnGrant.text = "Quyền đã được cấp"
        } else {
            tvStatus.text = "Ứng dụng cần quyền Đọc Thông Báo để tìm và điều khiển Media đang chạy ngầm."
            btnGrant.isEnabled = true
        }
    }
}