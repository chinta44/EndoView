package com.chinta44.endoview

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Geek szitman "supercamera" (VID 2CE3 / PID 3828、UseePlus 対応内視鏡) 用の
 * 独自プロトコルドライバ。この機種は UVC ではなくベンダー独自プロトコル
 * (com.useeplus.protocol) で JPEG をバルク転送してくる。
 *
 * プロトコル (有志による解析・実機キャプチャで確認済みの公開情報に基づく):
 * - 映像インターフェース (protocol=1) の代替設定を有効化し、OUT へ BB AA 05 00 00 を送ると配信開始
 * - IN パケットは 12 バイトヘッダ (AA BB / CID / 長さLE / フレームID / フラグ) + JPEG データ
 * - ヘッダを除いたデータを連結し、FF D8 .. FF D9 で切り出すと 640x480 の JPEG フレームになる
 * - ヘッダのフラグ byte[7] bit1 は本体の物理ボタン (シャッター)
 * - 画質調整は UVC Processing Unit 形式のコントロール転送で効く (明るさ/コントラスト/シャープネス/ガンマ)
 */
class SupercamDriver(
    private val usbManager: UsbManager,
    private val log: (String) -> Unit,
    private val onStarted: () -> Unit,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onButtonPress: () -> Unit,
    private val onStopped: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var connection: UsbDeviceConnection? = null
    private var worker: Thread? = null

    companion object {
        fun isSupercam(device: UsbDevice): Boolean =
            (device.vendorId == 0x2CE3 && device.productId == 0x3828) ||
                (device.vendorId == 0x0329 && device.productId == 0x2022)

        // UVC Processing Unit のセレクタ
        const val PU_BRIGHTNESS = 0x02
        const val PU_CONTRAST = 0x03
        const val PU_SHARPNESS = 0x08
        const val PU_GAMMA = 0x09
    }

    fun start(device: UsbDevice) {
        if (running) return
        running = true
        worker = thread(name = "supercam-driver") { runSession(device) }
    }

    fun stop() {
        running = false
        try {
            connection?.close()
        } catch (e: Exception) {
            // 終了済みでもよい
        }
    }

    // ---------- 画質調整 (UVC PU 形式のコントロール転送) ----------

    @Synchronized
    fun getPuValue(selector: Int): Int? {
        val conn = connection ?: return null
        val buf = ByteArray(2)
        val n = try {
            conn.controlTransfer(0xA1, 0x81, selector shl 8, 0x0200, buf, 2, 500)
        } catch (e: Exception) {
            -1
        }
        if (n < 2) return null
        return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
    }

    @Synchronized
    fun setPuValue(selector: Int, value: Int): Boolean {
        val conn = connection ?: return false
        val data = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
        return try {
            conn.controlTransfer(0x21, 0x01, selector shl 8, 0x0200, data, 2, 500) >= 0
        } catch (e: Exception) {
            false
        }
    }

    // ---------- 本体 ----------

    private fun runSession(device: UsbDevice) {
        var conn: UsbDeviceConnection? = null
        var videoIface: UsbInterface? = null
        var stopReason = "切断されました"
        try {
            conn = usbManager.openDevice(device)
            if (conn == null) {
                stopReason = "USB接続を開けませんでした"
                return
            }
            connection = conn

            // インターフェースとエンドポイントを全部ログに出す (診断用)
            var iapOut: UsbEndpoint? = null
            var iapIface: UsbInterface? = null
            var videoIn: UsbEndpoint? = null
            var videoOut: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val eps = mutableListOf<String>()
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
                        else -> "ctrl"
                    }
                    eps.add(String.format("0x%02X %s %s", ep.address, dir, type))
                }
                log("IF${intf.id} proto=${intf.interfaceProtocol} EP:[${eps.joinToString()}]")
                if (intf.interfaceProtocol == 1 && videoIn == null) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
                    }
                    if (inEp != null && outEp != null) {
                        videoIn = inEp; videoOut = outEp; videoIface = intf
                    }
                }
                if (intf.interfaceProtocol == 0 && iapOut == null) {
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                        ) {
                            iapOut = ep; iapIface = intf
                            break
                        }
                    }
                }
            }
            val vIn = videoIn
            val vOut = videoOut
            val vIface = videoIface
            if (vIn == null || vOut == null || vIface == null) {
                stopReason = "映像インターフェースが見つかりません"
                return
            }
            sessionVideoOut = vOut

            // iAP インターフェースへのウェイクプローブ (純正アプリも送信している。失敗しても無視)
            val probeOut = iapOut
            val probeIface = iapIface
            if (probeOut != null && probeIface != null) {
                try {
                    conn.claimInterface(probeIface, true)
                    val n = conn.bulkTransfer(
                        probeOut,
                        byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10),
                        6, 1000
                    )
                    log("iAPプローブ送信: $n")
                } catch (e: Exception) {
                    log("iAPプローブ失敗 (無視): ${e.message}")
                }
            }

            // 映像インターフェースを掴んで代替設定を有効化
            if (!conn.claimInterface(vIface, true)) {
                stopReason = "映像インターフェースを掴めませんでした"
                return
            }
            try {
                conn.setInterface(vIface)
            } catch (e: Exception) {
                log("setInterface失敗 (無視): ${e.message}")
            }

            // 配信開始コマンド
            val sent = conn.bulkTransfer(
                vOut, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00), 5, 1000
            )
            log("配信開始コマンド送信: $sent")
            if (sent < 0) {
                stopReason = "配信開始コマンドを送れませんでした"
                return
            }
            onStarted()

            readLoop(conn, vIn)
        } catch (e: Exception) {
            stopReason = "例外: ${e.message}"
            log(stopReason)
        } finally {
            if (running) {
                // 自然終了時のみ停止コマンドを試みる
                try {
                    val c = conn
                    val out = currentVideoOut(c, device)
                    if (c != null && out != null) {
                        c.bulkTransfer(out, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x08, 0x00, 0x00), 5, 500)
                    }
                } catch (e: Exception) {
                    // 片付け中の失敗は無視
                }
            }
            running = false
            try {
                videoIface?.let { conn?.releaseInterface(it) }
            } catch (e: Exception) {
                // 無視
            }
            try {
                conn?.close()
            } catch (e: Exception) {
                // 無視
            }
            if (connection === conn) connection = null
            onStopped(stopReason)
        }
    }

    private var sessionVideoOut: UsbEndpoint? = null

    private fun currentVideoOut(conn: UsbDeviceConnection?, device: UsbDevice): UsbEndpoint? =
        sessionVideoOut

    private fun readLoop(conn: UsbDeviceConnection, videoIn: UsbEndpoint) {
        val readBuf = ByteArray(17408)
        val stream = ByteArrayOutputStream()
        val frame = ByteArrayOutputStream()
        val headCapture = ByteArrayOutputStream()
        var aabbSeen = false
        var headLogged = false
        var prevButtonBit = 0
        var totalRead = 0L

        while (running) {
            val n = try {
                conn.bulkTransfer(videoIn, readBuf, readBuf.size, 5000)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                // タイムアウトは継続。接続が閉じられた場合は running=false で抜ける
                if (!running) break
                continue
            }
            if (n == 0) continue
            totalRead += n
            if (headCapture.size() < 64) {
                headCapture.write(readBuf, 0, minOf(n, 64 - headCapture.size()))
            }
            stream.write(readBuf, 0, n)

            // AA BB パケット単位に切り分ける
            var data = stream.toByteArray()
            var keepFrom = 0
            var pos = 0
            while (true) {
                val idx = indexOfHeader(data, pos)
                if (idx < 0) {
                    keepFrom = maxOf(keepFrom, data.size - 1)
                    break
                }
                if (data.size - idx < 5) {
                    keepFrom = idx
                    break
                }
                val total = ((data[idx + 3].toInt() and 0xFF) or
                    ((data[idx + 4].toInt() and 0xFF) shl 8)) + 5
                if (total < 12 || total > 65536) {
                    pos = idx + 1
                    keepFrom = idx + 1
                    continue
                }
                if (data.size - idx < total) {
                    keepFrom = idx
                    break
                }
                aabbSeen = true
                val cid = data[idx + 2].toInt() and 0xFF
                if (cid == 7 || cid == 10) {
                    val flags = data[idx + 7].toInt() and 0xFF
                    val bit = flags and 0x02
                    if (bit != 0 && prevButtonBit == 0) {
                        onButtonPress()
                    }
                    prevButtonBit = bit
                    frame.write(data, idx + 12, total - 12)
                }
                pos = idx + total
                keepFrom = pos
            }
            stream.reset()
            if (keepFrom < data.size) {
                stream.write(data, keepFrom, data.size - keepFrom)
            }

            if (!aabbSeen && !headLogged && totalRead > 2000) {
                headLogged = true
                val head = headCapture.toByteArray()
                log("警告: AA BB パケットが来ていません。先頭データ: " +
                    head.joinToString(" ") { String.format("%02X", it) })
            }

            // JPEG フレームの切り出し
            carveJpegs(frame)
        }
        log("読み取り終了 (受信 $totalRead バイト)")
    }

    private fun carveJpegs(frame: ByteArrayOutputStream) {
        var fb = frame.toByteArray()
        while (true) {
            val soi = indexOfMarker(fb, 0, 0xD8)
            if (soi < 0) {
                if (fb.size > 65536) frame.reset()
                return
            }
            val eoi = indexOfMarker(fb, soi + 2, 0xD9)
            if (eoi < 0) {
                if (soi > 0) {
                    frame.reset()
                    frame.write(fb, soi, fb.size - soi)
                }
                if (fb.size > 1_000_000) frame.reset()
                return
            }
            val jpeg = fb.copyOfRange(soi, eoi + 2)
            onJpegFrame(jpeg)
            val rest = fb.size - (eoi + 2)
            frame.reset()
            if (rest > 0) frame.write(fb, eoi + 2, rest)
            fb = frame.toByteArray()
        }
    }

    private fun indexOfHeader(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < data.size) {
            if (data[i] == 0xAA.toByte() && data[i + 1] == 0xBB.toByte()) return i
            i++
        }
        return -1
    }

    private fun indexOfMarker(data: ByteArray, from: Int, marker: Int): Int {
        var i = from
        while (i + 1 < data.size) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == marker.toByte()) return i
            i++
        }
        return -1
    }
}
