package com.chinta44.endoview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * 独自プロトコル機との接続をアプリ全体で保持するセッション。
 * 画面 (Activity) が回転などで作り直されても接続は切れず、
 * 新しい画面が Listener を登録し直して映像を引き継ぐ。
 */
object VendorSession {

    interface Listener {
        fun onLog(msg: String)
        fun onStreamingStarted()
        fun onFrame(bmp: Bitmap)
        fun onButtonPress()
        fun onStopped(reason: String)
    }

    @Volatile
    var driver: SupercamDriver? = null
        private set

    @Volatile
    var active = false
        private set

    @Volatile
    var streaming = false
        private set

    @Volatile
    var device: UsbDevice? = null
        private set

    @Volatile
    var lastJpegBytes: ByteArray? = null
        private set

    @Volatile
    var lastFrameBitmap: Bitmap? = null
        private set

    @Volatile
    var listener: Listener? = null

    fun start(usbManager: UsbManager, device: UsbDevice, yuvDecoder: (ByteArray) -> Bitmap?) {
        if (active) return
        active = true
        this.device = device
        var newDriver: SupercamDriver? = null
        newDriver = SupercamDriver(
            usbManager = usbManager,
            log = { msg -> listener?.onLog(msg) },
            onStarted = {
                streaming = true
                listener?.onStreamingStarted()
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
                    listener?.onFrame(bmp)
                }
            },
            onYuvFrame = { yuv ->
                val bmp = yuvDecoder(yuv)
                if (bmp != null) {
                    lastFrameBitmap = bmp
                    listener?.onFrame(bmp)
                }
            },
            onButtonPress = { listener?.onButtonPress() },
            onStopped = { reason ->
                // 自分が現行ドライバのときだけ後片付けする (stop() 済みの場合は無視)
                if (driver === newDriver) {
                    driver = null
                    active = false
                    streaming = false
                    listener?.onStopped(reason)
                }
            }
        )
        driver = newDriver
        newDriver.start(device)
    }

    fun stop() {
        val current = driver
        driver = null
        val wasActive = active
        active = false
        streaming = false
        current?.stop()
        if (wasActive) {
            listener?.onStopped("切断しました")
        }
    }
}
