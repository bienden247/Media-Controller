package com.khanhtran.aamediabridgenonroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat

class SteeringWheelBridgeService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private var trackedController: MediaController? = null

    private val handler = Handler(Looper.getMainLooper())
    private var revertRunnable: Runnable? = null

    // 🛡️ LÁ CHẮN BẢO VỆ MÃ QR: Ngăn chặn các app khác chèn ép
    private var isShowingDonate = false

    // ❤️ NHỊP TIM: Tự động cập nhật dữ liệu mỗi 2 giây (Chống bệnh ngủ đông)
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isShowingDonate) {
                forceSyncData()
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ĐIỆP VIÊN LẮNG NGHE TÍN HIỆU
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            if (isShowingDonate) return // Đang hiện QR thì cấm cập nhật nhạc
            forceSyncData()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            super.onMetadataChanged(metadata)
            if (isShowingDonate) return // Đang hiện QR thì cấm cập nhật nhạc
            trackedController?.let { updateDisplayTitle(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AABridgeSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }
                override fun onPause() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
                override fun onSkipToNext() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
                override fun onSkipToPrevious() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                override fun onSeekTo(pos: Long) { trackedController?.transportControls?.seekTo(pos) }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId == "action_donate") {
                        showDonateScreen()
                    } else if (mediaId != null && mediaId.startsWith("switch_media_")) {
                        // KHI TÀI XẾ BẤM CHUYỂN MEDIA
                        val targetPackage = mediaId.removePrefix("switch_media_")
                        try {
                            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                            val component = ComponentName(this@SteeringWheelBridgeService, MediaNotificationListener::class.java)
                            val controllers = mediaSessionManager.getActiveSessions(component)

                            // Tìm chính xác bộ điều khiển của app được chọn
                            val targetController = controllers.find { it.packageName == targetPackage }
                            if (targetController != null) {
                                // 1. Chuyển quyền theo dõi
                                trackedController?.unregisterCallback(controllerCallback)
                                trackedController = targetController
                                trackedController?.registerCallback(controllerCallback)

                                // 2. Cập nhật giao diện màn hình chính
                                syncPlaybackState(targetController.playbackState)
                                updateDisplayTitle(targetController)

                                // 3. CÚ CHỐT: Ra lệnh PLAY ngay lập tức để app mới cướp âm thanh
                                targetController.transportControls?.play()
                            }
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        }
                    }
                }
            })
        }
        sessionToken = mediaSession.sessionToken

        // Khởi động Nhịp tim
        handler.post(heartbeatRunnable)
    }

    // HÀM ÉP HỆ THỐNG QUÉT LẠI DỮ LIỆU TỨC THỜI
    private fun forceSyncData() {
        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val controllers = mediaSessionManager.getActiveSessions(component)
            findAndTrackActiveController(controllers)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun findAndTrackActiveController(controllers: List<MediaController>?) {
        if (controllers == null || isShowingDonate) return
        var target: MediaController? = null

        val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val allowedApps = prefs.getStringSet("allowed_apps", setOf()) ?: setOf()

        for (controller in controllers) {
            val pkgName = controller.packageName
            if (pkgName != packageName && allowedApps.contains(pkgName)) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    target = controller
                    break
                }
            }
        }

        if (target == null) {
            for (controller in controllers) {
                val pkgName = controller.packageName
                if (pkgName != packageName && allowedApps.contains(pkgName)) {
                    target = controller
                    break
                }
            }
        }

        if (target != null) {
            if (trackedController?.sessionToken != target.sessionToken) {
                trackedController?.unregisterCallback(controllerCallback)
                trackedController = target
                trackedController?.registerCallback(controllerCallback)
            }
            syncPlaybackState(target.playbackState)
            updateDisplayTitle(target)
        } else {
            // KHÔNG CÓ NHẠC: Ném phao cứu sinh lên màn hình để chống treo
            trackedController?.unregisterCallback(controllerCallback)
            trackedController = null

            val pauseState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                .build()
            mediaSession.setPlaybackState(pauseState)

            val emptyMetadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Sẵn sàng")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Vui lòng mở app")
                .build()
            mediaSession.setMetadata(emptyMetadata)
        }
    }

    private fun syncPlaybackState(targetState: PlaybackState?) {
        if (targetState == null) return
        val position = targetState.position
        val speed = targetState.playbackSpeed
        val compatState = when (targetState.state) {
            PlaybackState.STATE_PLAYING -> {
                mediaSession.isActive = true
                PlaybackStateCompat.STATE_PLAYING
            }
            PlaybackState.STATE_PAUSED -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(compatState, position, speed)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun sendMediaKey(keyCode: Int) {
        if (trackedController != null) {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            trackedController!!.dispatchMediaButtonEvent(downEvent)
            trackedController!!.dispatchMediaButtonEvent(upEvent)
        } else {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun updateDisplayTitle(targetController: MediaController) {
        val targetPackageName = targetController.packageName
        val appName = try {
            val pm = packageManager
            val applicationInfo = pm.getApplicationInfo(targetPackageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "Ứng dụng ($targetPackageName)"
        }

        val metadata = targetController.metadata
        val songTitle = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val songArtist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        var albumArt: Bitmap? = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (albumArt == null) {
            albumArt = metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
        }
        val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L

        val displayTitle = if (!songTitle.isNullOrEmpty()) songTitle else "Đang phát trên $appName"
        val displayArtist = if (!songArtist.isNullOrEmpty()) songArtist else appName

        val newMetadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)

        if (albumArt != null) {
            newMetadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
        }
        if (duration > 0) {
            newMetadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }

        mediaSession.setMetadata(newMetadataBuilder.build())
    }

    // HÀM HIỂN THỊ MÀN HÌNH DONATE
    private fun showDonateScreen() {
        isShowingDonate = true // Bật lá chắn bảo vệ QR

        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(state)

        val qrBitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.qr_donate)
        } catch (e: Exception) { null }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "☕ Cảm ơn bạn đã ủng hộ!")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Màn hình sẽ tự đóng sau 15s")

        if (qrBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, qrBitmap)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        revertRunnable?.let { handler.removeCallbacks(it) }
        revertRunnable = Runnable {
            isShowingDonate = false // Tắt lá chắn sau 30s
            forceSyncData() // Ép cập nhật lại nhạc đang phát
        }
        handler.postDelayed(revertRunnable!!, 15000)
    }

    private fun getInstalledAppInfo(packageName: String): String? {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("root_id", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == "root_id") {
            // 1. THƯ MỤC CHUYỂN ĐỔI MEDIA
            val switchFolder = MediaDescriptionCompat.Builder()
                .setMediaId("folder_media_switch")
                .setTitle("🔄 Trình phát đang mở")
                .setSubtitle("Chuyển quyền điều khiển sang app khác")
                .build()
            items.add(MediaBrowserCompat.MediaItem(switchFolder, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))

            val welcomeItem = MediaDescriptionCompat.Builder()
                .setMediaId("welcome_message")
                .setTitle("Media Controller đã sẵn sàng!")
                .setSubtitle("Mở video bất kì trên Youtube để bắt đầu")
                .build()
            items.add(MediaBrowserCompat.MediaItem(welcomeItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

            val donateItem = MediaDescriptionCompat.Builder()
                .setMediaId("action_donate")
                .setTitle("☕ Mời tôi ly Cafe (Donate)")
                .build()
            items.add(MediaBrowserCompat.MediaItem(donateItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        } else if (parentId == "folder_media_switch") {
            // 2. TÌM VÀ LIỆT KÊ CÁC APP ĐANG CÓ MEDIA SESSION
            try {
                val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(this, MediaNotificationListener::class.java)
                val controllers = mediaSessionManager.getActiveSessions(component)

                val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val allowedApps = prefs.getStringSet("allowed_apps", setOf()) ?: setOf()

                for (controller in controllers) {
                    val pkgName = controller.packageName
                    // Chỉ liệt kê app nằm trong danh sách được phép
                    if (pkgName != packageName && allowedApps.contains(pkgName)) {
                        val appName = getInstalledAppInfo(pkgName) ?: pkgName

                        // Đánh dấu xem app nào đang được chọn
                        val isCurrent = trackedController?.packageName == pkgName
                        val title = if (isCurrent) "✅ Đang điều khiển: $appName" else "▶️ Chuyển sang: $appName"

                        val appItem = MediaDescriptionCompat.Builder()
                            .setMediaId("switch_media_$pkgName")
                            .setTitle(title)
                            .setSubtitle("Bấm để đổi nhạc sang ứng dụng này")
                            .build()
                        items.add(MediaBrowserCompat.MediaItem(appItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                    }
                }

                if (items.isEmpty()) {
                    val emptyItem = MediaDescriptionCompat.Builder()
                        .setMediaId("empty_switch")
                        .setTitle("Không có app media nào đang chạy ngầm")
                        .setSubtitle("Vui lòng mở nhạc trên điện thoại trước!")
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(emptyItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        result.sendResult(items)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null) // Dọn dẹp Nhịp tim và bộ đếm QR khi đóng app
        trackedController?.unregisterCallback(controllerCallback)
        mediaSession.release()
        super.onDestroy()
    }
}