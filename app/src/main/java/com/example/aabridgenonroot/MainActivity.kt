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

    private lateinit var btnBatteryOpt: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupDefaultAppsFirstTime()

        checkAndShowOemWarning()

        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)

        btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
        btnBatteryOpt.setOnClickListener {
            // HIT 1: Xin quyền Android gốc trước
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)

            // HIT 2: Kiểm tra nếu là máy Trung Quốc thì bồi thêm cảnh báo
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            val chineseBrands = listOf("xiaomi", "oppo", "vivo", "realme", "huawei", "honor", "oneplus", "poco", "redmi", "iqoo")

            if (chineseBrands.any { manufacturer.contains(it) }) {
                val brandName = manufacturer.replaceFirstChar { it.uppercase() }

                // Dùng Handler delay 1 giây để đợi cái bảng của Android gốc hiện lên trước
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚠️ Bước 2 dành riêng cho $brandName")
                        .setMessage("Quyền bạn vừa cấp chỉ là của Android gốc.\n\nHệ thống $brandName vẫn có thể tự tắt app này.\n\nVui lòng bấm TIẾP TỤC để mở cài đặt của máy, tìm mục 'Pin' rồi ấn 'Cho phép chạy nền' nhé!")
                        .setPositiveButton("TIẾP TỤC") { _, _ ->
                            openBatteryOptimizationSettings() // Gọi hàm mở cài đặt của hãng
                        }
                        .setNegativeButton("BỎ QUA", null)
                        .show()
                }, 1000)
            }
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
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(packageName)

        if (isIgnoringBattery) {
            // NÂNG CẤP UX: Kiểm tra hãng máy để đổi chữ nhắc nhở khi nút bị mờ
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            val chineseBrands = listOf("xiaomi", "oppo", "vivo", "realme", "huawei", "honor", "oneplus", "poco", "redmi", "iqoo")

            if (chineseBrands.any { manufacturer.contains(it) }) {
                // Nếu là máy Trung Quốc: Nhắc khéo họ bật tính năng của hãng
                btnBatteryOpt.text = "ĐÃ CẤP (Nhớ bật Tự khởi chạy và tắt Tối ưu hoá pin)"
            } else {
                // Nếu là Samsung/Pixel: Chỉ cần hiện chữ bình thường
                btnBatteryOpt.text = "ĐÃ CHO PHÉP CHẠY NGẦM"
            }
            btnBatteryOpt.isEnabled = false // Làm mờ nút
        } else {
            btnBatteryOpt.text = "TẮT TỐI ƯU HÓA PIN"
            btnBatteryOpt.isEnabled = true // Sáng nút
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
    private fun checkAndShowOemWarning() {
        val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)

        // Nếu người dùng đã bấm "Đã hiểu" trước đó rồi thì không làm phiền nữa
        if (prefs.getBoolean("has_seen_oem_warning", false)) return

        // Lấy tên hãng sản xuất điện thoại (chuyển hết thành chữ thường cho dễ so sánh)
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()

        // Danh sách các "sát thủ diệt app ngầm" khét tiếng
        val chineseBrands = listOf("xiaomi", "oppo", "vivo", "realme", "huawei", "honor", "oneplus", "poco", "redmi", "iqoo")

        // Nếu hãng điện thoại nằm trong danh sách đen
        // Nếu hãng điện thoại nằm trong danh sách đen
        if (chineseBrands.any { manufacturer.contains(it) }) {

            val brandName = manufacturer.replaceFirstChar { it.uppercase() }

            AlertDialog.Builder(this)
                .setTitle("⚠️ Lưu ý cho máy $brandName")
                .setMessage("Hệ điều hành của máy bạn thường tự động 'đóng băng' ứng dụng chạy ngầm để tiết kiệm pin.\n\nĐiều này có thể làm tính năng chuyển App trên xe bị lỗi, hoặc vô lăng không bấm được.\n\nĐể app hoạt động hoàn hảo, vui lòng:\n1. Khóa app này trong Đa nhiệm.\n2. Bật 'Cho phép chạy ngầm' / 'Tự khởi chạy' trong Cài đặt.")
                // SỬA Ở ĐÂY: Khi bấm nút thì gọi hàm điều hướng
                .setPositiveButton("THIẾT LẬP NGAY") { _, _ ->
                    prefs.edit().putBoolean("has_seen_oem_warning", true).apply()
                    openBatteryOptimizationSettings() // Lệnh bay thẳng vào cài đặt
                }
                // Thêm nút "Để sau" cho người dùng lựa chọn (không nên ép buộc quá đáng)
                .setNegativeButton("Để sau") { _, _ ->
                    prefs.edit().putBoolean("has_seen_oem_warning", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent()
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()

            // 1. Dò tìm cửa chính của từng hãng
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") -> {
                    intent.component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    intent.component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                }
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    intent.component = android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                }
                else -> {
                    throw Exception("Không tìm thấy hãng") // Ép nhảy xuống vòng catch
                }
            }
            startActivity(intent) // Thử phi thẳng vào cửa chính

        } catch (e: Exception) {
            // 2. NẾU CỬA CHÍNH BỊ ĐỔI TÊN HOẶC LỖI -> Đi cửa phụ (Mở App Info)
            try {
                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                fallbackIntent.data = android.net.Uri.parse("package:$packageName")
                startActivity(fallbackIntent)
                Toast.makeText(this, "Vui lòng vào Mức sử dụng Pin -> Cho phép hoạt động ngầm", Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}