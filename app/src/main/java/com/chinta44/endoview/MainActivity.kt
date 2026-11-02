package com.chinta44.endoview

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chinta44.endoview.databinding.ActivityMainBinding
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.IButtonCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCControl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EndoView v0.1
 * 既存アプリ相当の基本機能 (プレビュー / 写真 / 動画 / 解像度 / 回転 / 反転) +
 * 見やすさ重視 (UVC調整スライダーとプリセット / フリーズ拡大 / ピンチズーム / グリッド)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraHelper: ICameraHelper? = null
    private var currentDevice: UsbDevice? = null
    private var pendingDevice: UsbDevice? = null
    private var connected = false
    private var recording = false
    private var frozen = false
    private var rotation = 0
    private var mirrorMode = MirrorMode.MIRROR_NORMAL

    private val handler = Handler(Looper.getMainLooper())
    private var recordStart = 0L
    private val recordTick = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - recordStart) / 1000
            binding.recordTime.text = String.format(Locale.JAPAN, "● %02d:%02d", sec / 60, sec % 60)
            handler.postDelayed(this, 500)
        }
    }

    // ---------- UVC 調整スライダーの定義 ----------
    private inner class UvcSlider(
        val label: String,
        val isEnable: (UVCControl) -> Boolean,
        val updateLimit: (UVCControl) -> IntArray?,
        val getRaw: (UVCControl) -> Int,
        val setRaw: (UVCControl, Int) -> Unit,
        val resetRaw: (UVCControl) -> Unit
    ) {
        var min = 0
        var max = 100
        var seekBar: SeekBar? = null
        var valueText: TextView? = null

        fun prepare(control: UVCControl): Boolean {
            return try {
                if (!isEnable(control)) return false
                val limit = updateLimit(control)
                if (limit != null && limit.size >= 2 && limit[1] > limit[0]) {
                    min = limit[0]; max = limit[1]
                } else {
                    min = 0; max = 100
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "prepare $label failed", e)
                false
            }
        }

        fun getPercent(control: UVCControl): Int {
            if (max <= min) return 50
            val raw = getRaw(control)
            return ((raw - min) * 100 / (max - min)).coerceIn(0, 100)
        }

        fun setPercent(control: UVCControl, percent: Int) {
            val raw = min + (max - min) * percent / 100
            setRaw(control, raw)
            valueText?.text = "$percent%"
        }
    }

    private fun controlDefs(): List<UvcSlider> = listOf(
        UvcSlider("明るさ",
            { it.isBrightnessEnable }, { it.updateBrightnessLimit() },
            { it.brightness }, { c, v -> c.setBrightness(v) }, { it.resetBrightness() }),
        UvcSlider("コントラスト",
            { it.isContrastEnable }, { it.updateContrastLimit() },
            { it.contrast }, { c, v -> c.setContrast(v) }, { it.resetContrast() }),
        UvcSlider("シャープネス",
            { it.isSharpnessEnable }, { it.updateSharpnessLimit() },
            { it.sharpness }, { c, v -> c.setSharpness(v) }, { it.resetSharpness() }),
        UvcSlider("ガンマ",
            { it.isGammaEnable }, { it.updateGammaLimit() },
            { it.gamma }, { c, v -> c.setGamma(v) }, { it.resetGamma() }),
        UvcSlider("ゲイン",
            { it.isGainEnable }, { it.updateGainLimit() },
            { it.gain }, { c, v -> c.setGain(v) }, { it.resetGain() }),
        UvcSlider("彩度",
            { it.isSaturationEnable }, { it.updateSaturationLimit() },
            { it.saturation }, { c, v -> c.setSaturation(v) }, { it.resetSaturation() }),
        UvcSlider("逆光補正",
            { it.isBacklightCompEnable }, { it.updateBacklightCompLimit() },
            { it.backlightComp }, { c, v -> c.setBacklightComp(v) }, { it.resetBacklightComp() }),
        UvcSlider("ホワイトバランス",
            { it.isWhiteBalanceEnable }, { it.updateWhiteBalanceLimit() },
            { it.whiteBalance }, { c, v -> c.setWhiteBalance(v) }, { it.resetWhiteBalance() }),
        UvcSlider("露出 (手動)",
            { it.isExposureTimeAbsoluteEnable }, { it.updateExposureTimeAbsoluteLimit() },
            { it.exposureTimeAbsolute },
            { c, v ->
                // 露出を触ったらオート露出を切って手動に切り替える
                try { c.setExposureTimeAuto(false) } catch (e: Exception) { Log.w(TAG, "exposure manual failed", e) }
                c.setExposureTimeAbsolute(v)
            },
            { it.resetExposureTimeAbsolute() }),
        UvcSlider("フォーカス",
            { it.isFocusAbsoluteEnable }, { it.updateFocusAbsoluteLimit() },
            { it.focusAbsolute }, { c, v -> c.setFocusAbsolute(v) }, { it.resetFocusAbsolute() }),
        UvcSlider("光学ズーム",
            { it.isZoomAbsoluteEnable }, { it.updateZoomAbsoluteLimit() },
            { it.zoomAbsolute }, { c, v -> c.setZoomAbsolute(v) }, { it.resetZoomAbsolute() })
    )

    private var sliders: List<UvcSlider> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupPreviewSurface()
        setupZoomGestures()
        setupButtons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbDeviceFromIntent(intent)?.let { attachDevice(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        initCameraHelper()
    }

    override fun onStop() {
        super.onStop()
        if (recording) stopRecording()
        clearCameraHelper()
    }

    // ---------- カメラ初期化 ----------

    private fun initCameraHelper() {
        if (cameraHelper == null) {
            val helper = CameraHelper()
            helper.setStateCallback(stateCallback)
            try {
                helper.setImageCaptureConfig(
                    helper.getImageCaptureConfig().setJpegCompressionQuality(92)
                )
                helper.setVideoCaptureConfig(
                    helper.getVideoCaptureConfig()
                        .setAudioCaptureEnable(false) // 内視鏡動画は無音が基本。マイク権限も不要になる
                        .setVideoFrameRate(25)
                        .setBitRate(8 * 1024 * 1024)
                        .setIFrameInterval(1)
                )
            } catch (e: Exception) {
                Log.w(TAG, "capture config failed", e)
            }
            cameraHelper = helper
        }
    }

    private fun clearCameraHelper() {
        cameraHelper?.release()
        cameraHelper = null
        connected = false
    }

    private fun setupPreviewSurface() {
        binding.preview.setAspectRatio(640, 480)
        binding.preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                cameraHelper?.addSurface(surface, false)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraHelper?.removeSurface(surface)
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun usbDeviceFromIntent(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun attachDevice(device: UsbDevice) {
        if (currentDevice == null && !connected) {
            currentDevice = device
            selectDevice(device)
        }
    }

    private fun selectDevice(device: UsbDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDevice = device
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        cameraHelper?.selectDevice(device)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val device = pendingDevice
            pendingDevice = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && device != null) {
                cameraHelper?.selectDevice(device)
            } else {
                toast("カメラ権限が必要です")
            }
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            attachDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val helper = cameraHelper ?: return
            helper.openCamera()
            // 内視鏡側の物理ボタン (シャッター) でも写真を撮れるようにする
            helper.setButtonCallback(IButtonCallback { _, state ->
                if (state == 1) runOnUiThread { takePicture() }
            })
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            helper.startPreview()
            binding.preview.surfaceTexture?.let { helper.addSurface(it, false) }
            helper.previewSize?.let { size ->
                binding.preview.setAspectRatio(size.width, size.height)
            }
            connected = true
            runOnUiThread {
                updateConnectedUI()
                showDeviceInfo(device)
                buildControls()
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            if (recording) stopRecording()
            binding.preview.surfaceTexture?.let { cameraHelper?.removeSurface(it) }
            connected = false
            runOnUiThread { updateConnectedUI() }
        }

        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {
            if (device == currentDevice) currentDevice = null
        }

        override fun onCancel(device: UsbDevice) {
            if (device == currentDevice) currentDevice = null
        }
    }

    private fun showDeviceInfo(device: UsbDevice) {
        val size = cameraHelper?.previewSize
        val name = device.productName ?: device.deviceName
        binding.deviceInfo.text = String.format(
            Locale.JAPAN, "%s  VID:%04X PID:%04X%s",
            name, device.vendorId, device.productId,
            if (size != null) "  ${size.width}×${size.height}" else ""
        )
        binding.deviceInfo.visibility = View.VISIBLE
    }

    private fun updateConnectedUI() {
        binding.statusTip.visibility = if (connected) View.GONE else View.VISIBLE
        if (!connected) binding.deviceInfo.visibility = View.GONE
        listOf(
            binding.btnPhoto, binding.btnFreeze, binding.btnControls,
            binding.btnRotate, binding.btnMirror, binding.btnResolution
        ).forEach { it.isEnabled = connected }
        binding.btnRecord.isEnabled = connected
        if (!connected) binding.controlsPanel.visibility = View.GONE
    }

    // ---------- ボタン類 ----------

    private fun setupButtons() {
        binding.btnPhoto.setOnClickListener { takePicture() }
        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
        binding.btnFreeze.setOnClickListener { toggleFreeze() }
        binding.btnControls.setOnClickListener {
            binding.controlsPanel.visibility =
                if (binding.controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnRotate.setOnClickListener { rotateBy90() }
        binding.btnMirror.setOnClickListener { cycleMirror() }
        binding.btnGrid.setOnClickListener {
            binding.gridOverlay.visibility =
                if (binding.gridOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.btnResolution.setOnClickListener { showResolutionDialog() }

        binding.presetDefault.setOnClickListener { applyPreset(null) }
        binding.presetDark.setOnClickListener {
            applyPreset(mapOf("明るさ" to 70, "ゲイン" to 75, "ガンマ" to 70, "コントラスト" to 55, "逆光補正" to 60))
        }
        binding.presetMetal.setOnClickListener {
            applyPreset(mapOf("明るさ" to 35, "ガンマ" to 40, "ゲイン" to 30, "コントラスト" to 60, "逆光補正" to 20))
        }
        binding.presetBoard.setOnClickListener {
            applyPreset(mapOf("シャープネス" to 85, "コントラスト" to 70, "彩度" to 25, "明るさ" to 55, "ガンマ" to 55))
        }
        binding.btnResetControls.setOnClickListener { applyPreset(null) }
        binding.btnExposureAuto.setOnClickListener {
            val control = cameraHelper?.uvcControl ?: return@setOnClickListener
            try {
                control.setExposureTimeAuto(true)
                toast("露出をオートに戻しました")
            } catch (e: Exception) {
                toast("このカメラは露出オート切替に未対応です")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 片手操作用: 音量ボタンでもシャッターを切れる
        if (connected && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            takePicture()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 調整パネル ----------

    private fun buildControls() {
        val control = cameraHelper?.uvcControl
        binding.slidersContainer.removeAllViews()
        sliders = emptyList()
        if (control == null) {
            addNoControlNote("カメラ調整を取得できませんでした")
            return
        }
        val built = mutableListOf<UvcSlider>()
        for (def in controlDefs()) {
            if (!def.prepare(control)) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val label = TextView(this).apply {
                text = def.label
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val initial = try { def.getPercent(control) } catch (e: Exception) { 50 }
            val value = TextView(this).apply {
                text = "$initial%"
                setTextColor(0xFFFFD54F.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            val seek = SeekBar(this).apply {
                max = 100
                progress = initial
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val c = cameraHelper?.uvcControl ?: return
                        try { def.setPercent(c, progress) } catch (e: Exception) {
                            Log.w(TAG, "set ${def.label} failed", e)
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            def.seekBar = seek
            def.valueText = value
            row.addView(label)
            row.addView(seek)
            row.addView(value)
            binding.slidersContainer.addView(row)
            built.add(def)
        }
        sliders = built
        if (built.isEmpty()) {
            addNoControlNote("このカメラはアプリからの画質調整に未対応です (明るさはケーブルのダイヤルで調整してください)")
        }
    }

    private fun addNoControlNote(text: String) {
        val note = TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(4, 12, 4, 12)
        }
        binding.slidersContainer.addView(note)
    }

    /** preset が null のときは全リセット。それ以外は指定した項目だけ％で適用する */
    private fun applyPreset(preset: Map<String, Int>?) {
        val control = cameraHelper?.uvcControl ?: return
        try {
            if (preset == null) {
                sliders.forEach { def ->
                    try { def.resetRaw(control) } catch (e: Exception) { Log.w(TAG, "reset ${def.label}", e) }
                }
                toast("調整を標準に戻しました")
            } else {
                sliders.forEach { def ->
                    val pct = preset[def.label] ?: return@forEach
                    def.setPercent(control, pct)
                    def.seekBar?.progress = pct
                }
                toast("プリセットを適用しました (対応項目のみ)")
            }
            // スライダー表示を実値に合わせ直す
            if (preset == null) {
                sliders.forEach { def ->
                    val pct = try { def.getPercent(control) } catch (e: Exception) { 50 }
                    def.seekBar?.progress = pct
                    def.valueText?.text = "$pct%"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "preset failed", e)
        }
    }

    // ---------- 写真 / 動画 ----------

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())

    private fun takePicture() {
        val helper = cameraHelper ?: return
        if (!connected || recording) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ENDO_${timestamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/EndoView")
        }
        val options = IImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        helper.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(results: IImageCapture.OutputFileResults) {
                runOnUiThread { toast("写真を保存しました (Pictures/EndoView)") }
            }

            override fun onError(code: Int, message: String, cause: Throwable?) {
                runOnUiThread { toast("写真の保存に失敗: $message") }
            }
        })
    }

    private fun startRecording() {
        val helper = cameraHelper ?: return
        if (!connected || recording) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ENDO_${timestamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/EndoView")
        }
        val options = VideoCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        helper.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
            override fun onStart() {
                runOnUiThread {
                    recording = true
                    binding.btnRecord.text = "⏹ 停止"
                    binding.recordTime.visibility = View.VISIBLE
                    recordStart = SystemClock.elapsedRealtime()
                    handler.post(recordTick)
                }
            }

            override fun onVideoSaved(results: VideoCapture.OutputFileResults) {
                runOnUiThread {
                    finishRecordingUi()
                    toast("動画を保存しました (Movies/EndoView)")
                }
            }

            override fun onError(code: Int, message: String, cause: Throwable?) {
                runOnUiThread {
                    finishRecordingUi()
                    toast("録画に失敗: $message")
                }
            }
        })
    }

    private fun stopRecording() {
        cameraHelper?.stopRecording()
        // UI の後片付けは onVideoSaved 側でも行うが、押下時点で止まった見た目にする
        handler.postDelayed({ if (recording) finishRecordingUi() }, 600)
    }

    private fun finishRecordingUi() {
        recording = false
        handler.removeCallbacks(recordTick)
        binding.recordTime.visibility = View.GONE
        binding.btnRecord.text = "🎥 録画"
    }

    // ---------- フリーズ / ズーム ----------

    private fun toggleFreeze() {
        if (frozen) {
            binding.freezeImage.visibility = View.GONE
            binding.freezeImage.setImageBitmap(null)
            frozen = false
            binding.btnFreeze.text = "❄ フリーズ"
            return
        }
        val bmp = try { binding.preview.bitmap } catch (e: Exception) { null }
        if (bmp == null) {
            toast("映像がまだ来ていません")
            return
        }
        binding.freezeImage.setImageBitmap(bmp)
        resetFreezeTransform()
        binding.freezeImage.visibility = View.VISIBLE
        frozen = true
        binding.btnFreeze.text = "▶ 再開"
        toast("フリーズ中: ピンチで拡大、ドラッグで移動できます")
    }

    private fun resetFreezeTransform() {
        binding.freezeImage.scaleX = 1f
        binding.freezeImage.scaleY = 1f
        binding.freezeImage.translationX = 0f
        binding.freezeImage.translationY = 0f
    }

    private fun setupZoomGestures() {
        // プレビュー: ピンチでデジタルズーム、ダブルタップで等倍に戻す
        var previewScale = 1f
        val previewScaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    previewScale = (previewScale * detector.scaleFactor).coerceIn(1f, 4f)
                    binding.preview.scaleX = previewScale
                    binding.preview.scaleY = previewScale
                    if (previewScale == 1f) {
                        binding.preview.translationX = 0f
                        binding.preview.translationY = 0f
                    }
                    return true
                }
            })
        val previewGesture = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    previewScale = 1f
                    binding.preview.scaleX = 1f
                    binding.preview.scaleY = 1f
                    binding.preview.translationX = 0f
                    binding.preview.translationY = 0f
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
                ): Boolean {
                    if (previewScale > 1f) {
                        binding.preview.translationX -= dx
                        binding.preview.translationY -= dy
                    }
                    return true
                }
            })
        binding.preview.setOnTouchListener { _, event ->
            previewScaleDetector.onTouchEvent(event)
            previewGesture.onTouchEvent(event)
            true
        }

        // フリーズ画像: 同じ操作で拡大確認
        var freezeScale = 1f
        val freezeScaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    freezeScale = (freezeScale * detector.scaleFactor).coerceIn(1f, 6f)
                    binding.freezeImage.scaleX = freezeScale
                    binding.freezeImage.scaleY = freezeScale
                    if (freezeScale == 1f) resetFreezeTransform()
                    return true
                }
            })
        val freezeGesture = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    freezeScale = 1f
                    resetFreezeTransform()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
                ): Boolean {
                    if (freezeScale > 1f) {
                        binding.freezeImage.translationX -= dx
                        binding.freezeImage.translationY -= dy
                    }
                    return true
                }
            })
        binding.freezeImage.setOnTouchListener { _, event ->
            freezeScaleDetector.onTouchEvent(event)
            freezeGesture.onTouchEvent(event)
            true
        }
    }

    // ---------- 回転 / 反転 / 解像度 ----------

    private fun rotateBy90() {
        val helper = cameraHelper ?: return
        rotation = (rotation + 90) % 360
        try {
            helper.setPreviewConfig(helper.getPreviewConfig().setRotation(rotation))
            toast("回転: ${rotation}°")
        } catch (e: Exception) {
            toast("回転に失敗しました")
        }
    }

    private fun cycleMirror() {
        val helper = cameraHelper ?: return
        mirrorMode = when (mirrorMode) {
            MirrorMode.MIRROR_NORMAL -> MirrorMode.MIRROR_HORIZONTAL
            MirrorMode.MIRROR_HORIZONTAL -> MirrorMode.MIRROR_VERTICAL
            MirrorMode.MIRROR_VERTICAL -> MirrorMode.MIRROR_BOTH
            else -> MirrorMode.MIRROR_NORMAL
        }
        try {
            helper.setPreviewConfig(helper.getPreviewConfig().setMirror(mirrorMode))
            toast(
                when (mirrorMode) {
                    MirrorMode.MIRROR_HORIZONTAL -> "左右反転"
                    MirrorMode.MIRROR_VERTICAL -> "上下反転"
                    MirrorMode.MIRROR_BOTH -> "上下左右反転"
                    else -> "反転なし"
                }
            )
        } catch (e: Exception) {
            toast("反転に失敗しました")
        }
    }

    private fun showResolutionDialog() {
        val helper = cameraHelper ?: return
        val sizes = try {
            helper.supportedSizeList
                .distinctBy { "${it.width}x${it.height}" }
                .sortedByDescending { it.width * it.height }
        } catch (e: Exception) {
            emptyList()
        }
        if (sizes.isNullOrEmpty()) {
            toast("対応解像度を取得できませんでした")
            return
        }
        val labels = sizes.map { "${it.width} × ${it.height}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("解像度")
            .setItems(labels) { _, which ->
                val size: Size = sizes[which]
                try {
                    helper.stopPreview()
                    helper.setPreviewSize(size)
                    helper.startPreview()
                    binding.preview.setAspectRatio(size.width, size.height)
                    currentDevice?.let { showDeviceInfo(it) }
                } catch (e: Exception) {
                    toast("解像度の変更に失敗しました")
                }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "EndoView"
        private const val REQ_CAMERA = 1001
    }
}
