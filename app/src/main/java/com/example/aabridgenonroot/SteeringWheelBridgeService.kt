package com.khanhtran.aamediabridgenonroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat


class SteeringWheelBridgeService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private var trackedController: MediaController? = null

    // Biến tự nhớ trạng thái lặp lại
    private var myInternalRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE

    // Điệp viên theo dõi YouTube
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (myInternalRepeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
                trackedController?.let { controller ->
                    val metadata = controller.metadata
                    val state = controller.playbackState

                    if (metadata != null && state != null && state.state == PlaybackState.STATE_PLAYING) {
                        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
                        val timeDelta = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                        val currentPosition = state.position + (timeDelta * state.playbackSpeed).toLong()

                        if (duration > 0 && currentPosition >= duration - 1500L) {
                            controller.transportControls?.seekTo(0)
                            controller.transportControls?.play()
                        }
                    }
                }
            }
            watchdogHandler.postDelayed(this, 1000)
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncPlaybackState(state)
        }
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            updateDisplayTitle(trackedController)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "SteeringWheelBridge")
        sessionToken = mediaSession.sessionToken
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }
            override fun onPause() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            override fun onSkipToNext() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
            override fun onSkipToPrevious() { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            override fun onSeekTo(pos: Long) { trackedController?.transportControls?.seekTo(pos) }

            override fun onFastForward() {
                trackedController?.let { controller ->
                    val currentPos = controller.playbackState?.position ?: 0L
                    controller.transportControls?.seekTo(currentPos + 10000L)
                }
            }

            override fun onRewind() {
                trackedController?.let { controller ->
                    val currentPos = controller.playbackState?.position ?: 0L
                    val newPos = if (currentPos > 10000L) currentPos - 10000L else 0L
                    controller.transportControls?.seekTo(newPos)
                }
            }

            override fun onCustomAction(action: String?, extras: Bundle?) {
                trackedController?.let { controller ->
                    val currentPos = controller.playbackState?.position ?: 0L
                    when (action) {
                        "ACTION_FORWARD_10" -> {
                            controller.transportControls?.seekTo(currentPos + 10000L)
                        }
                        "ACTION_REWIND_10" -> {
                            val newPos = if (currentPos > 10000L) currentPos - 10000L else 0L
                            controller.transportControls?.seekTo(newPos)
                        }
                        "ACTION_TOGGLE_REPEAT" -> {
                            myInternalRepeatMode = when (myInternalRepeatMode) {
                                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                                PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ONE
                                else -> PlaybackStateCompat.REPEAT_MODE_NONE
                            }

                            try {
                                val compatToken = android.support.v4.media.session.MediaSessionCompat.Token.fromToken(controller.sessionToken)
                                val compatController = android.support.v4.media.session.MediaControllerCompat(this@SteeringWheelBridgeService, compatToken)
                                compatController.transportControls.setRepeatMode(myInternalRepeatMode)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            syncPlaybackState(controller.playbackState)
                        }
                    }
                }
            }

            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                if (mediaId == "action_donate") {
                    // 1. TẠO BÀI HÁT GIẢ VỚI ẢNH BÌA LÀ MÃ QR
                    val qrBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.qr_donate)
                    val donateMetadata = android.support.v4.media.MediaMetadataCompat.Builder()
                        .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, "☕ Cảm ơn bạn đã ủng hộ!")
                        .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, "Màn hình sẽ tự đóng sau 15s")
                        .putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, qrBitmap)
                        .build()

                    // 2. ÉP ANDROID AUTO HIỂN THỊ TRÌNH PHÁT NHẠC
                    mediaSession.setMetadata(donateMetadata)
                    val donateState = android.support.v4.media.session.PlaybackStateCompat.Builder()
                        .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                        .build()
                    mediaSession.setPlaybackState(donateState)

                    // 3. ĐẾM NGƯỢC 10 GIÂY RỒI TỰ ĐỘNG THU HỒI
                    Handler(Looper.getMainLooper()).postDelayed({
                        trackedController?.let { controller ->
                            updateDisplayTitle(controller)
                            syncPlaybackState(controller.playbackState)
                        } ?: run {
                            val emptyState = android.support.v4.media.session.PlaybackStateCompat.Builder()
                                .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED, 0, 0f).build()
                            mediaSession.setPlaybackState(emptyState)
                        }
                    }, 15000)

                } else if (mediaId != null && mediaId.startsWith("switch_media_")) {
                    val targetPackage = mediaId.removePrefix("switch_media_")
                    try {
                        val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                        val component = ComponentName(this@SteeringWheelBridgeService, MediaNotificationListener::class.java)
                        val controllers = mediaSessionManager.getActiveSessions(component)

                        val targetController = controllers.find { it.packageName == targetPackage }
                        if (targetController != null) {
                            trackedController?.unregisterCallback(controllerCallback)
                            trackedController = targetController
                            trackedController?.registerCallback(controllerCallback)

                            syncPlaybackState(targetController.playbackState)
                            updateDisplayTitle(targetController)

                            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                                Handler(Looper.getMainLooper()).postDelayed({
                                    targetController.transportControls?.play()
                                }, 500)
                            } else {
                                targetController.transportControls?.play()
                            }

                            notifyChildrenChanged("folder_media_switch")
                        }
                    } catch (e: SecurityException) {
                        e.printStackTrace()
                    }
                }
            }
        })

        watchdogHandler.postDelayed(watchdogRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        trackedController?.unregisterCallback(controllerCallback)
        mediaSession.isActive = false
        mediaSession.release()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("root_id", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == "root_id") {
            val switchFolder = MediaDescriptionCompat.Builder()
                .setMediaId("folder_media_switch")
                .setTitle("Trình phát đang mở")
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
                .setTitle("☕ Mời tôi ly Cafe")
                .setSubtitle("Bấm vào đây để hiển thị mã QR!")
                .build()
            items.add(MediaBrowserCompat.MediaItem(donateItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

        } else if (parentId == "folder_media_switch") {
            try {
                val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(this, MediaNotificationListener::class.java)
                val controllers = mediaSessionManager.getActiveSessions(component)

                val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val allowedApps = prefs.getStringSet("allowed_apps", setOf()) ?: setOf()

                for (controller in controllers) {
                    val pkgName = controller.packageName
                    if (pkgName != packageName && allowedApps.contains(pkgName)) {
                        val appName = getInstalledAppInfo(pkgName) ?: pkgName
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

        val repeatIcon = when (myInternalRepeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one_on
            PlaybackStateCompat.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on
            else -> R.drawable.ic_repeat
        }

        val repeatLabel = when (myInternalRepeatMode) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> "Đang tắt (Bấm để Lặp tất cả)"
            PlaybackStateCompat.REPEAT_MODE_ALL -> "Đang lặp tất cả (Bấm để Lặp 1 bài)"
            else -> "Đang lặp 1 bài (Bấm để Tắt)"
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            )
            .addCustomAction("ACTION_REWIND_10", "Tua lùi 10s", R.drawable.ic_replay_10)
            .addCustomAction("ACTION_FORWARD_10", "Tua đi 10s", R.drawable.ic_forward_10)
            .addCustomAction("ACTION_TOGGLE_REPEAT", repeatLabel, repeatIcon)
            .setState(compatState, position, speed)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateDisplayTitle(targetController: MediaController?) {
        if (targetController == null) return
        val metadata = targetController.metadata
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Không rõ bài hát"
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Không rõ nghệ sĩ"

        val mediaMetadataCompat = android.support.v4.media.MediaMetadataCompat.fromMediaMetadata(metadata)
        mediaSession.setMetadata(mediaMetadataCompat)
        mediaSession.setMetadata(mediaMetadataCompat)
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            trackedController?.dispatchMediaButtonEvent(eventDown)
            trackedController?.dispatchMediaButtonEvent(eventUp)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getInstalledAppInfo(packageName: String): String? {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }
}