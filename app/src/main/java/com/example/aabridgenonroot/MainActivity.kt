package com.khanhtran.aamediabridgenonroot // Thay bằng tên gói của bạn nếu cần

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

// 1. DATA CLASS: Cái hộp chứa dữ liệu của 1 ứng dụng
data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isChecked: Boolean
)

// 2. CUSTOM ADAPTER: Bộ phận chuyển đổi dữ liệu thành giao diện hiển thị
class AppListAdapter(
    context: Context,
    private val apps: List<AppItem>
) : ArrayAdapter<AppItem>(context, 0, apps) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app_list, parent, false)
        val app = apps[position]

        val iconView = view.findViewById<ImageView>(R.id.appIcon)
        val nameView = view.findViewById<TextView>(R.id.appName)
        val packageView = view.findViewById<TextView>(R.id.appPackage)
        val checkBox = view.findViewById<CheckBox>(R.id.appCheckBox)

        iconView.setImageDrawable(app.icon)
        nameView.text = app.name
        packageView.text = app.packageName
        checkBox.isChecked = app.isChecked

        return view
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var btnGrantPermission: Button
    private lateinit var btnSelectApps: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupDefaultAppsFirstTime()

        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnSelectApps = findViewById(R.id.btnSelectApps)

        btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        btnSelectApps.setOnClickListener {
            showAppSelectionDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndUpdatePermissionButton()
    }

    private fun checkAndUpdatePermissionButton() {
        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (isGranted) {
            btnGrantPermission.text = "ĐÃ CẤP QUYỀN"
            btnGrantPermission.isEnabled = false
        } else {
            btnGrantPermission.text = "CẤP QUYỀN TRUY CẬP"
            btnGrantPermission.isEnabled = true
        }
    }

    private fun setupDefaultAppsFirstTime() {
        val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        if (!prefs.contains("allowed_apps")) {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val appListInfo = pm.queryIntentActivities(intent, 0)

            val defaultAllowed = mutableSetOf<String>()
            for (info in appListInfo) {
                val pkgName = info.activityInfo.packageName
                if (pkgName.contains("youtube", ignoreCase = true) && !pkgName.contains("music", ignoreCase = true)) {
                    defaultAllowed.add(pkgName)
                }
            }
            prefs.edit().putStringSet("allowed_apps", defaultAllowed).apply()
        }
    }

    // 3. HÀM HIỂN THỊ CỬA SỔ CHỌN APP VỚI GIAO DIỆN MỚI
    private fun showAppSelectionDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val appListInfo = pm.queryIntentActivities(intent, 0)

        appListInfo.sortBy { it.loadLabel(pm).toString() }

        val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val allowedApps = prefs.getStringSet("allowed_apps", setOf())?.toMutableSet() ?: mutableSetOf()

        // Lấy toàn bộ Icon, Tên, Package Name nhét vào cái hộp AppItem
        val appItems = appListInfo.map { info ->
            AppItem(
                name = info.loadLabel(pm).toString(),
                packageName = info.activityInfo.packageName,
                icon = info.activityInfo.loadIcon(pm), // Lấy Icon gốc của App
                isChecked = allowedApps.contains(info.activityInfo.packageName)
            )
        }

        // Tạo một cái ListView động và nạp Adapter tự chế vào
        val listView = ListView(this)
        val adapter = AppListAdapter(this, appItems)
        listView.adapter = adapter

        // Bắt sự kiện khi người dùng chọt ngón tay vào 1 dòng
        listView.setOnItemClickListener { _, _, position, _ ->
            val app = appItems[position]
            app.isChecked = !app.isChecked // Bật/Tắt dấu check
            adapter.notifyDataSetChanged() // Vẽ lại dòng đó

            if (app.isChecked) {
                allowedApps.add(app.packageName)
            } else {
                allowedApps.remove(app.packageName)
            }
        }

        // Nhét ListView vào giữa cửa sổ nổi
        AlertDialog.Builder(this)
            .setTitle("Chọn ứng dụng cho phép")
            .setView(listView)
            .setPositiveButton("OK") { _, _ ->
                prefs.edit().putStringSet("allowed_apps", allowedApps).apply()
                Toast.makeText(this, "Đã lưu thành công!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}