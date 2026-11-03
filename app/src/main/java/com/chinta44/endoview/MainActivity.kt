package com.chinta44.endoview

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.chinta44.endoview.databinding.ActivityMainBinding
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.IButtonCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCControl
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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

    // v0.1.1 診断用: カメラ起動のリトライ段階と接続ログ
    private var openRetryStage = 0
    private var launchIntentHandled = false
    private val logLines = mutableListOf<String>()

    // v0.2.0 独自プロトコル機 (supercamera) 用
    private var vendorDriver: SupercamDriver? = null
    private var vendorActive = false
    private var vendorPendingDevice: UsbDevice? = null
    @Volatile
    private var lastJpegBytes: ByteArray? = null
    @Volatile
    private var lastFrameBitmap: Bitmap? = null
    private var mirrorState = 0 // 0:なし 1:左右 2:上下 3:両方 (ベンダー経路用)

    private inner class VendorSlider(val label: String, val selector: Int) {
        var seekBar: SeekBar? = null
        var valueText: TextView? = null
    }

    private var vendorSliders: List<VendorSlider> = emptyList()

    // v0.3.0 仕上げ (録画 / ログ表示切替)
    private var vendorRecorder: VendorRecorder? = null
    private var pendingVideoUri: Uri? = null
    private var logVisible = true

    // v0.4.0 (保存先 / 没入表示)
    private val prefs by lazy { getSharedPreferences("endoview", MODE_PRIVATE) }
    private val idleHandler = Handler(Looper.getMainLooper())
    private var controlsHidden = false
    private val hideControlsRunnable = Runnable { hideControls() }
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 権限が取れなくても URI は保存し、書き込み時に判定する
            }
            prefs.edit().putString("save_tree", uri.toString())
                .putString("save_dest", "saf").apply()
            toast("保存先フォルダを設定しました")
        }
    }

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
        title = "EndoView 内視鏡 v${appVersionName()}"
        supportActionBar?.hide()
        WindowInsetsControllerCompat(window, binding.root).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        logVisible = prefs.getBoolean("log_visible", true)
        binding.debugLog.visibility = if (logVisible) View.VISIBLE else View.GONE
        logEvent("EndoView v${appVersionName()} を起動しました")

        setupPreviewSurface()
        setupZoomGestures()
        setupButtons()
        registerUsbReceiver()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // 未登録でもよい
        }
        super.onDestroy()
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
        // USB接続インテントで起動された場合、そのデバイスを拾う (初回のみ)
        if (!launchIntentHandled) {
            launchIntentHandled = true
            usbDeviceFromIntent(intent)?.let {
                logEvent("起動インテントからデバイスを検出")
                attachDevice(it)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        idleHandler.removeCallbacks(hideControlsRunnable)
        if (controlsHidden) showControls()
        if (recording) stopRecording()
        stopVendorDriver()
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

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
                applyPreviewFill()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraHelper?.removeSurface(surface)
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        binding.preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreviewFill()
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
        if (SupercamDriver.isSupercam(device)) {
            handleVendorAttach(device)
            return
        }
        if (currentDevice == null && !connected) {
            currentDevice = device
            logEvent("接続を開始します")
            selectDevice(device)
        }
    }

    // ---------- v0.1.1 診断ログ ----------

    private fun logEvent(msg: String) {
        Log.d(TAG, msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
        synchronized(logLines) {
            logLines.add("$time $msg")
            while (logLines.size > 9) logLines.removeAt(0)
        }
        runOnUiThread {
            binding.debugLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            binding.debugLog.text = synchronized(logLines) { logLines.joinToString("\n") }
        }
    }

    private fun describeDevice(device: UsbDevice): String {
        val ifs = StringBuilder()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (i > 0) ifs.append(", ")
            ifs.append("${intf.interfaceClass}/${intf.interfaceSubclass}/${intf.interfaceProtocol}")
        }
        return String.format(
            Locale.JAPAN, "%s VID:%04X PID:%04X class:%d/%d/%d IF:[%s]",
            device.productName ?: device.deviceName, device.vendorId, device.productId,
            device.deviceClass, device.deviceSubclass, device.deviceProtocol, ifs.toString()
        )
    }

    /** カメラ起動。失敗したら onError 側で段階を進めてここを呼び直す */
    private fun doOpenCamera(device: UsbDevice) {
        val helper = cameraHelper ?: return
        try {
            when (openRetryStage) {
                0 -> {
                    logEvent("カメラ起動を試みます (標準)")
                    helper.openCamera()
                }
                1 -> {
                    logEvent("再試行1: 帯域クアーク付きで起動")
                    helper.openCamera(UVCParam(null, UVCCamera.UVC_QUIRK_FIX_BANDWIDTH))
                }
                2 -> {
                    logEvent("再試行2: MJPEG 640x480 を指定して起動")
                    helper.openCamera(Size(UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480, 30, listOf(30)))
                }
                else -> {
                    logEvent("再試行3: MJPEG 640x480 + クアーク付きで起動")
                    helper.openCamera(
                        UVCParam(
                            Size(UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480, 30, listOf(30)),
                            UVCCamera.UVC_QUIRK_FIX_BANDWIDTH
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logEvent("起動呼び出しで例外: ${e.message}")
        }
    }

    /** 接続中 USB デバイスの一覧から手動で選んで接続する */
    private fun showDevicePicker() {
        val usbManager = getSystemService(UsbManager::class.java)
        val devices = usbManager?.deviceList?.values?.toList().orEmpty()
        if (devices.isEmpty()) {
            toast("USBデバイスが見つかりません")
            logEvent("デバイス一覧: なし")
            return
        }
        devices.forEach { logEvent("候補: ${describeDevice(it)}") }
        val labels = devices.map {
            String.format(
                Locale.JAPAN, "%s (VID:%04X PID:%04X)",
                it.productName ?: it.deviceName, it.vendorId, it.productId
            )
        }
        AlertDialog.Builder(this)
            .setTitle("接続するUSBデバイス")
            .setItems(labels.toTypedArray()) { _, which ->
                currentDevice = null
                openRetryStage = 0
                attachDevice(devices[which])
            }
            .setNegativeButton("キャンセル", null)
            .show()
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

    // ---------- v0.2.0 独自プロトコル機 (supercamera) 経路 ----------

    private val usbPermissionAction = "com.chinta44.endoview.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> {
                    val device = usbDeviceFromIntent(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val pending = vendorPendingDevice
                    vendorPendingDevice = null
                    if (granted && pending != null) {
                        logEvent("USB許可OK (ベンダー機)")
                        startVendorDriver(pending)
                    } else if (device != null || pending != null) {
                        logEvent("USB許可が拒否されました")
                        toast("USBの許可が必要です")
                        if (currentDevice == pending) currentDevice = null
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = usbDeviceFromIntent(intent)
                    if (device != null && vendorActive && device == currentDevice) {
                        logEvent("ベンダー機が取り外されました")
                        stopVendorDriver()
                        currentDevice = null
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun handleVendorAttach(device: UsbDevice) {
        if (vendorActive || vendorPendingDevice != null) return
        if (currentDevice != null && currentDevice != device && connected) return
        currentDevice = device
        logEvent("独自プロトコル機を検出 (supercamera系)。専用ドライバで接続します")
        val usbManager = getSystemService(UsbManager::class.java) ?: return
        if (usbManager.hasPermission(device)) {
            startVendorDriver(device)
        } else {
            vendorPendingDevice = device
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(usbPermissionAction).setPackage(packageName), flags
            )
            usbManager.requestPermission(device, pi)
            logEvent("USB許可を要求しました")
        }
    }

    private fun startVendorDriver(device: UsbDevice) {
        val usbManager = getSystemService(UsbManager::class.java) ?: return
        vendorActive = true
        binding.liveView.visibility = View.VISIBLE
        val driver = SupercamDriver(
            usbManager = usbManager,
            log = { msg -> logEvent(msg) },
            onStarted = {
                runOnUiThread {
                    connected = true
                    updateConnectedUI()
                    binding.deviceInfo.text = String.format(
                        Locale.JAPAN, "%s (独自プロトコル)  VID:%04X PID:%04X  640×480",
                        device.productName ?: device.deviceName, device.vendorId, device.productId
                    )
                    binding.deviceInfo.visibility = View.VISIBLE
                    buildControls()
                    toast("接続しました (640×480)")
                }
            },
            onJpegFrame = { jpeg ->
                val bmp = try {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) {
                    lastJpegBytes = jpeg
                    lastFrameBitmap = bmp
                    vendorRecorder?.feed(bmp)
                    if (!frozen) {
                        runOnUiThread { binding.liveView.setImageBitmap(bmp) }
                    }
                }
            },
            onYuvFrame = { yuv ->
                val bmp = yuvToBitmap(yuv)
                if (bmp != null) {
                    lastFrameBitmap = bmp
                    vendorRecorder?.feed(bmp)
                    if (!frozen) {
                        runOnUiThread { binding.liveView.setImageBitmap(bmp) }
                    }
                }
            },
            onButtonPress = {
                runOnUiThread {
                    logEvent("本体ボタンが押されました")
                    takePicture()
                }
            },
            onStopped = { reason ->
                runOnUiThread {
                    logEvent("ベンダー接続終了: $reason")
                    if (vendorActive) {
                        vendorActive = false
                        connected = false
                        binding.liveView.visibility = View.GONE
                        updateConnectedUI()
                    }
                }
            }
        )
        vendorDriver = driver
        driver.start(device)
    }

    /** YUYV422 (640x480) の生フレームを Bitmap に変換する */
    private fun yuvToBitmap(yuv: ByteArray): Bitmap? {
        val width = 640
        val height = 480
        if (yuv.size < width * height * 2) return null
        val pixels = IntArray(width * height)
        var i = 0
        var p = 0
        while (i + 3 < yuv.size && p < pixels.size - 1) {
            val y0 = yuv[i].toInt() and 0xFF
            val u = (yuv[i + 1].toInt() and 0xFF) - 128
            val y1 = yuv[i + 2].toInt() and 0xFF
            val v = (yuv[i + 3].toInt() and 0xFF) - 128
            pixels[p] = yuvPixel(y0, u, v)
            pixels[p + 1] = yuvPixel(y1, u, v)
            i += 4
            p += 2
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun yuvPixel(y: Int, u: Int, v: Int): Int {
        val r = (y + ((359 * v) shr 8)).coerceIn(0, 255)
        val g = (y - ((88 * u + 183 * v) shr 8)).coerceIn(0, 255)
        val b = (y + ((454 * u) shr 8)).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun stopVendorDriver() {
        vendorDriver?.stop()
        vendorDriver = null
    }

    private fun buildVendorControls() {
        binding.slidersContainer.removeAllViews()
        sliders = emptyList()
        val defs = listOf(
            VendorSlider("明るさ", SupercamDriver.PU_BRIGHTNESS),
            VendorSlider("コントラスト", SupercamDriver.PU_CONTRAST),
            VendorSlider("シャープネス", SupercamDriver.PU_SHARPNESS),
            VendorSlider("ガンマ", SupercamDriver.PU_GAMMA)
        )
        for (def in defs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val label = TextView(this).apply {
                text = def.label
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            val value = TextView(this).apply {
                text = "50%"
                setTextColor(0xFFFFD54F.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            }
            val seek = SeekBar(this).apply {
                max = 100
                progress = 50
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.2f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        def.valueText?.text = "$progress%"
                        val raw = progress * 255 / 100
                        val driver = vendorDriver
                        thread { driver?.setPuValue(def.selector, raw) }
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
        }
        vendorSliders = defs
        // 現在値を読み取って表示に反映する
        val driver = vendorDriver
        thread {
            for (def in defs) {
                val raw = driver?.getPuValue(def.selector) ?: continue
                val pct = (raw * 100 / 255).coerceIn(0, 100)
                runOnUiThread {
                    def.seekBar?.progress = pct
                    def.valueText?.text = "$pct%"
                }
            }
        }
    }

    private fun applyVendorPreset(preset: Map<String, Int>?) {
        val driver = vendorDriver ?: return
        thread {
            for (def in vendorSliders) {
                val pct = if (preset == null) 50 else (preset[def.label] ?: continue)
                val raw = pct * 255 / 100
                driver.setPuValue(def.selector, raw)
                runOnUiThread {
                    def.seekBar?.progress = pct
                    def.valueText?.text = "$pct%"
                }
            }
        }
        toast(if (preset == null) "調整を標準に戻しました" else "プリセットを適用しました (対応項目のみ)")
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            logEvent("検出: ${describeDevice(device)}")
            attachDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            val helper = cameraHelper ?: return
            logEvent("デバイスを開きました")
            openRetryStage = 0
            doOpenCamera(device)
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
            val sizes = try { helper.supportedSizeList } catch (e: Exception) { null }
            val sizeText = sizes?.let { list ->
                "対応サイズ${list.size}件: " + list.take(6).joinToString { "${it.width}x${it.height}" }
            } ?: ""
            logEvent("カメラ起動OK ${helper.previewSize?.width}x${helper.previewSize?.height} $sizeText")
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

        override fun onDeviceClose(device: UsbDevice) {
            logEvent("デバイスを閉じました")
        }

        override fun onDetach(device: UsbDevice) {
            logEvent("取り外されました")
            if (vendorActive && device == currentDevice) stopVendorDriver()
            if (device == currentDevice) currentDevice = null
        }

        override fun onCancel(device: UsbDevice) {
            logEvent("キャンセルされました (権限拒否など)")
            if (device == currentDevice) currentDevice = null
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            val kind = when (e.code) {
                CameraException.CAMERA_OPEN_ERROR_BUSY -> "デバイス占有"
                CameraException.CAMERA_OPEN_ERROR_UNKNOWN -> "不明なエラー"
                else -> "コード${e.code}"
            }
            logEvent("カメラ起動エラー: $kind (${e.message})")
            if (device == currentDevice && !connected && openRetryStage < 3) {
                openRetryStage++
                handler.postDelayed({
                    if (device == currentDevice && !connected) doOpenCamera(device)
                }, 700)
            } else if (!connected) {
                runOnUiThread {
                    binding.statusTip.text =
                        "カメラを開けませんでした。抜き差しするか、下のボタンから選び直してください"
                }
            }
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
        binding.btnDevices.visibility = if (connected) View.GONE else View.VISIBLE
        listOf(
            binding.btnPhoto, binding.btnFreeze, binding.btnControls,
            binding.btnRotate, binding.btnMirror, binding.btnResolution
        ).forEach { it.isEnabled = connected }
        binding.btnRecord.isEnabled = connected
        binding.btnResolution.isEnabled = connected && !vendorActive
        // 解像度が固定の独自プロトコル機ではボタンを丸ごと隠す
        binding.btnResolution.visibility = if (vendorActive) View.GONE else View.VISIBLE
        if (!connected) binding.controlsPanel.visibility = View.GONE
        if (connected) scheduleControlsHide()
    }

    // ---------- ボタン類 ----------

    private fun setupButtons() {
        binding.btnDevices.setOnClickListener { showDevicePicker() }
        binding.btnSaveDest.setOnClickListener { showSaveDestDialog() }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnLogToggle.setOnClickListener {
            logVisible = !logVisible
            binding.debugLog.visibility = if (logVisible) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("log_visible", logVisible).apply()
            toast(if (logVisible) "接続ログを表示します" else "接続ログを隠します")
        }
        binding.btnPhoto.setOnClickListener { takePicture() }
        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else startRecording()
        }
        binding.btnFreeze.setOnClickListener { toggleFreeze() }
        binding.btnControls.setOnClickListener {
            val opening = binding.controlsPanel.visibility != View.VISIBLE
            binding.controlsPanel.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) {
                idleHandler.removeCallbacks(hideControlsRunnable)
            } else {
                scheduleControlsHide()
            }
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
            if (vendorActive) {
                toast("この内視鏡では露出の切替に未対応です")
                return@setOnClickListener
            }
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
        if (vendorActive) {
            buildVendorControls()
            return
        }
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
        if (vendorActive) {
            applyVendorPreset(preset)
            return
        }
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

    // ---------- 保存先 ----------

    private fun saveDestKey(): String = prefs.getString("save_dest", "endoview") ?: "endoview"

    private fun photoRelativePath(): String = when (saveDestKey()) {
        "camera" -> "DCIM/Camera"
        "root" -> "Pictures"
        "download" -> "Download"
        else -> "Pictures/EndoView"
    }

    private fun videoRelativePath(): String = when (saveDestKey()) {
        "camera" -> "DCIM/Camera"
        "root" -> "Movies"
        "download" -> "Download"
        else -> "Movies/EndoView"
    }

    private fun saveDestLabel(isVideo: Boolean): String = when (saveDestKey()) {
        "saf" -> "指定フォルダ"
        "camera" -> "DCIM/Camera"
        "root" -> if (isVideo) "Movies" else "Pictures"
        "download" -> "Download"
        else -> if (isVideo) "Movies/EndoView" else "Pictures/EndoView"
    }

    /** 保存先の URI を作る。フォルダ指定 (SAF) が使えない場合は MediaStore にフォールバック */
    private fun createSaveUri(name: String, mime: String, isVideo: Boolean, pending: Boolean): Uri? {
        if (saveDestKey() == "saf") {
            createSafUri(name, mime)?.let { return it }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) videoRelativePath() else photoRelativePath())
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return contentResolver.insert(collection, values)
    }

    private fun createSafUri(name: String, mime: String): Uri? {
        val treeStr = prefs.getString("save_tree", null) ?: return null
        return try {
            val treeUri = Uri.parse(treeStr)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(contentResolver, parent, mime, name)
        } catch (e: Exception) {
            null
        }
    }

    private fun showSaveDestDialog() {
        val keys = arrayOf("endoview", "camera", "root", "download", "saf")
        val labels = arrayOf(
            "EndoView フォルダ (標準)", "カメラと同じ場所 (DCIM/Camera)",
            "Pictures / Movies の直下", "ダウンロード (Download)", "フォルダを指定…"
        )
        val current = keys.indexOf(saveDestKey()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("保存先")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (keys[which] == "saf") {
                    folderPicker.launch(null)
                } else {
                    prefs.edit().putString("save_dest", keys[which]).apply()
                    toast("保存先を変更しました")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- 没入表示 (操作系の自動非表示) ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (controlsHidden) showControls()
            scheduleControlsHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scheduleControlsHide() {
        idleHandler.removeCallbacks(hideControlsRunnable)
        idleHandler.postDelayed(hideControlsRunnable, 3500)
    }

    private fun hideControls() {
        if (!connected || recording || frozen) return
        if (binding.controlsPanel.visibility == View.VISIBLE) return
        controlsHidden = true
        listOf(binding.topBar, binding.sideBar, binding.bottomBar).forEach { v ->
            v.animate().alpha(0f).setDuration(250)
                .withEndAction { v.visibility = View.INVISIBLE }.start()
        }
        WindowInsetsControllerCompat(window, binding.root)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showControls() {
        controlsHidden = false
        listOf(binding.topBar, binding.sideBar, binding.bottomBar).forEach { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(200).start()
        }
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** UVC プレビューを画面いっぱいに表示する (はみ出し分は切り取る) */
    private fun applyPreviewFill() {
        val pv = binding.preview
        val container = binding.previewContainer
        if (pv.width == 0 || pv.height == 0 || container.width == 0 || container.height == 0) return
        val z = maxOf(
            container.width.toFloat() / pv.width,
            container.height.toFloat() / pv.height
        )
        val m = Matrix()
        if (z > 1f) m.setScale(z, z, pv.width / 2f, pv.height / 2f)
        pv.setTransform(m)
    }

    private fun setRecordUi(rec: Boolean) {
        binding.btnRecord.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_video)
        binding.btnRecord.backgroundTintList =
            if (rec) ColorStateList.valueOf(Color.parseColor("#B71C1C")) else null
    }

    // ---------- 写真 / 動画 ----------

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())

    private fun takePicture() {
        if (vendorActive) {
            takeVendorPicture()
            return
        }
        val helper = cameraHelper ?: return
        if (!connected || recording) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ENDO_${timestamp()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, photoRelativePath())
        }
        val options = IImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        helper.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(results: IImageCapture.OutputFileResults) {
                runOnUiThread { toast("写真を保存しました (${saveDestLabel(false)})") }
            }

            override fun onError(code: Int, message: String, cause: Throwable?) {
                runOnUiThread { toast("写真の保存に失敗: $message") }
            }
        })
    }

    /** ベンダー経路の写真: 最新 JPEG フレームを回転/反転を反映して保存する */
    private fun takeVendorPicture() {
        if (!connected || recording) return
        val jpeg = lastJpegBytes
        val bmp = lastFrameBitmap
        if (bmp == null) {
            toast("映像がまだ来ていません")
            return
        }
        thread {
            val bytes = if (rotation == 0 && mirrorState == 0 && jpeg != null) {
                jpeg
            } else {
                val matrix = Matrix()
                val mx = if (mirrorState == 1 || mirrorState == 3) -1f else 1f
                val my = if (mirrorState >= 2) -1f else 1f
                matrix.setScale(mx, my)
                if (rotation != 0) matrix.postRotate(rotation.toFloat())
                val transformed = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                val out = ByteArrayOutputStream()
                transformed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
            val uri = createSaveUri("ENDO_${timestamp()}.jpg", "image/jpeg", false, false)
            var ok = false
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ok = true
            }
            runOnUiThread {
                toast(if (ok) "写真を保存しました (${saveDestLabel(false)})" else "写真の保存に失敗しました")
            }
        }
    }

    /** ベンダー経路の録画開始 (H.264 / MP4、無音) */
    private fun startVendorRecording() {
        if (!connected || recording) return
        val uri = createSaveUri("ENDO_${timestamp()}.mp4", "video/mp4", true, true)
        if (uri == null) {
            toast("録画を開始できませんでした")
            return
        }
        val pfd = try {
            contentResolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            contentResolver.delete(uri, null, null)
            toast("録画を開始できませんでした")
            return
        }
        pendingVideoUri = uri
        val rec = VendorRecorder { ok ->
            runOnUiThread {
                val doneUri = pendingVideoUri
                pendingVideoUri = null
                vendorRecorder = null
                if (doneUri != null) {
                    if (ok) {
                        if (doneUri.authority == "media") {
                            val done = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                            }
                            try {
                                contentResolver.update(doneUri, done, null, null)
                            } catch (e: Exception) {
                                // 無視
                            }
                        }
                        toast("動画を保存しました (${saveDestLabel(true)})")
                    } else {
                        try {
                            contentResolver.delete(doneUri, null, null)
                        } catch (e: Exception) {
                            // 無視
                        }
                        toast("録画に失敗しました")
                    }
                }
                finishRecordingUi()
            }
        }
        if (rec.start(pfd)) {
            vendorRecorder = rec
            recording = true
            setRecordUi(true)
            binding.recordTime.visibility = View.VISIBLE
            recordStart = SystemClock.elapsedRealtime()
            handler.post(recordTick)
        } else {
            pendingVideoUri = null
            contentResolver.delete(uri, null, null)
            toast("録画を開始できませんでした")
        }
    }

    private fun stopVendorRecording() {
        vendorRecorder?.stop()
        // UI の後片付けは onFinished 側で行う。応答がない場合の保険
        handler.postDelayed({ if (recording && vendorRecorder == null) finishRecordingUi() }, 4000)
    }

    private fun startRecording() {
        if (vendorActive) {
            startVendorRecording()
            return
        }
        val helper = cameraHelper ?: return
        if (!connected || recording) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ENDO_${timestamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, videoRelativePath())
        }
        val options = VideoCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        helper.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
            override fun onStart() {
                runOnUiThread {
                    recording = true
                    setRecordUi(true)
                    binding.recordTime.visibility = View.VISIBLE
                    recordStart = SystemClock.elapsedRealtime()
                    handler.post(recordTick)
                }
            }

            override fun onVideoSaved(results: VideoCapture.OutputFileResults) {
                runOnUiThread {
                    finishRecordingUi()
                    toast("動画を保存しました (${saveDestLabel(true)})")
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
        if (vendorActive) {
            stopVendorRecording()
            return
        }
        cameraHelper?.stopRecording()
        // UI の後片付けは onVideoSaved 側でも行うが、押下時点で止まった見た目にする
        handler.postDelayed({ if (recording) finishRecordingUi() }, 600)
    }

    private fun finishRecordingUi() {
        recording = false
        handler.removeCallbacks(recordTick)
        binding.recordTime.visibility = View.GONE
        setRecordUi(false)
    }

    // ---------- フリーズ / ズーム ----------

    private fun toggleFreeze() {
        if (frozen) {
            binding.freezeImage.visibility = View.GONE
            binding.freezeImage.setImageBitmap(null)
            frozen = false
            binding.btnFreeze.backgroundTintList = null
            return
        }
        val bmp = if (vendorActive) lastFrameBitmap
            else try { binding.preview.bitmap } catch (e: Exception) { null }
        if (bmp == null) {
            toast("映像がまだ来ていません")
            return
        }
        binding.freezeImage.setImageBitmap(bmp)
        resetFreezeTransform()
        binding.freezeImage.visibility = View.VISIBLE
        frozen = true
        binding.btnFreeze.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#EF6C00"))
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
        if (vendorActive) {
            rotation = (rotation + 90) % 360
            applyVendorTransform()
            toast("回転: ${rotation}°")
            return
        }
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
        if (vendorActive) {
            mirrorState = (mirrorState + 1) % 4
            applyVendorTransform()
            toast(
                when (mirrorState) {
                    1 -> "左右反転"
                    2 -> "上下反転"
                    3 -> "上下左右反転"
                    else -> "反転なし"
                }
            )
            return
        }
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

    /** ベンダー経路プレビュー (liveView) への回転/反転の反映 */
    private fun applyVendorTransform() {
        binding.liveView.rotation = rotation.toFloat()
        binding.liveView.scaleX = if (mirrorState == 1 || mirrorState == 3) -1f else 1f
        binding.liveView.scaleY = if (mirrorState >= 2) -1f else 1f
    }

    private fun showResolutionDialog() {
        if (vendorActive) {
            toast("この内視鏡は 640×480 固定です")
            return
        }
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

    private fun appVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "EndoView"
        private const val REQ_CAMERA = 1001
    }
}
