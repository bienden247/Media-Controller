package com.khanhtran.aamediabridgenonroot

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
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

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var revertRunnable: Runnable? = null

    // Blacklist App
    private val ignoredPackages = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "com.soundcloud.android",
        "com.aspiro.tidal",
        "deezer.android.app",
        "com.amazon.mp3",
        "com.zing.mp3",
        "com.nct.nhaccuatui",
        "com.fonos",
        "com.wefite.voiz",
        "au.com.shiftyjelly.pocketcasts",
        "com.audible.application",
        "com.google.android.apps.podcasts",
        "tunein.player",
        "com.clearchannel.iheartradio.controller"
    )

    // Theo dõi app
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            syncPlaybackState(state)
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            super.onMetadataChanged(metadata)
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
                    }
                }
            })
        }
        sessionToken = mediaSession.sessionToken

        val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        try {
            val component = ComponentName(this, MediaNotificationListener::class.java)

            mediaSessionManager.addOnActiveSessionsChangedListener({ controllers ->
                findAndTrackActiveController(controllers)
            }, component)

            findAndTrackActiveController(mediaSessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // Tìm và kích hoạt App
    private fun findAndTrackActiveController(controllers: List<MediaController>?) {
        if (controllers == null) return
        var target: MediaController? = null

        for (controller in controllers) {
            val pkgName = controller.packageName
            if (pkgName != packageName && !ignoredPackages.contains(pkgName)) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    target = controller
                    break
                }
            }
        }

        if (target == null) {
            for (controller in controllers) {
                val pkgName = controller.packageName
                if (pkgName != packageName && !ignoredPackages.contains(pkgName)) {
                    target = controller
                    break
                }
            }
        }

        if (target != null) {
            if (trackedController?.packageName != target.packageName) {
                trackedController?.unregisterCallback(controllerCallback)
                trackedController = target
                trackedController?.registerCallback(controllerCallback)
            }

            syncPlaybackState(target.playbackState)
            updateDisplayTitle(target)
        }
    }

    //Sync trạng thái và thanh tiến trình
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
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(compatState, position, speed)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    // Gửi lệnh vô lăng qua App
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

    //Update tên và ảnh bìa
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

    // Hiện QR
    private fun showDonateScreen() {
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
            .build()
        mediaSession.setPlaybackState(state)

        val qrBitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.qr_donate)
        } catch (e: Exception) {
            null
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "☕ Cảm ơn bạn đã ủng hộ!")
            // Đã đổi câu Subtitle để báo cho người dùng biết nó sẽ tự tắt
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Màn hình sẽ tự đóng sau 15s...")

        if (qrBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, qrBitmap)
        }

        mediaSession.setMetadata(metadataBuilder.build())


        // 1. Hủy lệnh cũ (đề phòng người dùng bấm nút Donate nhiều lần liên tục)
        revertRunnable?.let { handler.removeCallbacks(it) }

        // 2. Lên kế hoạch trả lại màn hình gốc
        revertRunnable = Runnable {
            // Nếu có bài YouTube nào đang phát ngầm lúc đó
            if (trackedController != null) {
                // Ép điệp viên cập nhật lại trạng thái và ảnh bìa gốc lên màn hình xe
                syncPlaybackState(trackedController!!.playbackState)
                updateDisplayTitle(trackedController!!)
            } else {
                // Nếu lúc đó không mở nhạc gì cả, thì chuyển màn hình về trạng thái Tạm dừng
                val pauseState = PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f)
                    .build()
                mediaSession.setPlaybackState(pauseState)
            }
        }

        // 3. Bấm giờ chạy! (15000 ms = 15 giây)
        handler.postDelayed(revertRunnable!!, 15000)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot("root_id", null)
    }

    //Lời chào và Donate
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        if (parentId == "root_id") {
            val welcomeItem = MediaDescriptionCompat.Builder()
                .setMediaId("welcome_message")
                .setTitle("Media Controller đã sẵn sàng!")
                .setSubtitle("Mở video bất kì trên YouTube để bắt đầu")
                .build()
            items.add(MediaBrowserCompat.MediaItem(welcomeItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))

            val donateItem = MediaDescriptionCompat.Builder()
                .setMediaId("action_donate")
                .setTitle("☕ Mời Dev ly Cafe")
                .setSubtitle("Bấm vào đây để quét mã QR nhé!")
                .build()
            items.add(MediaBrowserCompat.MediaItem(donateItem, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }

        result.sendResult(items)
    }

    override fun onDestroy() {
        trackedController?.unregisterCallback(controllerCallback)
        mediaSession.release()
        super.onDestroy()
    }
}