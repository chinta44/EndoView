package com.chinta44.endoview

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Geek szitman "supercamera" (VID 2CE3 / PID 3828、UseePlus 対応内視鏡) 用の
 * 独自プロトコルドライバ。この機種は UVC ではなくベンダー独自プロトコル
 * (com.useeplus.protocol) を使う。
 *
 * 確立した接続手順 (実機で確認済み):
 *  1. iAP インターフェースの IN から挨拶 FF 55 02 00 EE 10 を読み取る
 *  2. iAP の OUT へプローブ FF 55 FF 55 EE 10 を送る
 *  3. 映像インターフェース (protocol=1) の OUT へ BB AA 05 00 00 を送ると
 *     944 バイトパケット (12 バイトヘッダ AA BB + JPEG データ) の配信が始まる
 * 個体差に備え、失敗した場合は「プローブなしの BB AA 05」「コントロール転送
 * (YUYV 生フレーム)」の順にフォールバックする。
 *
 * フレーム組み立てはヘッダのフレーム ID (byte[5]) 単位で行い、先頭 SOI と
 * 末尾 EOI が揃ったフレームだけを渡す。不完全なフレームは表示せず捨てる
 * (砂嵐・フラッシュ防止)。
 * ヘッダのフラグ byte[7] bit1 は本体の物理ボタン。
 * 画質調整は UVC Processing Unit 形式のコントロール転送
 * (明るさ/コントラスト/シャープネス/ガンマ)。
 */
class SupercamDriver(
    private val usbManager: UsbManager,
    private val log: (String) -> Unit,
    private val onStarted: () -> Unit,
    private val onJpegFrame: (ByteArray) -> Unit,
    private val onYuvFrame: (ByteArray) -> Unit,
    private val onButtonPress: () -> Unit,
    private val onStopped: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var connection: UsbDeviceConnection? = null
    private var worker: Thread? = null

    // ストリーム解析用の状態
    private val streamBuf = ByteArrayOutputStream()
    private val rawBuf = ByteArrayOutputStream()
    private val currentFrame = ByteArrayOutputStream()
    private var currentFrameId = -1
    private var aabbSeen = false
    private var prevButtonBit = 0
    private var totalReadBytes = 0L
    private var readCount = 0
    private var droppedFrames = 0
    private var emittedFrames = 0

    companion object {
        private const val TAG = "SupercamDriver"
        const val FRAME_BYTES = 614400 // 640x480 YUYV422

        fun isSupercam(device: UsbDevice): Boolean =
            (device.vendorId == 0x2CE3 && device.productId == 0x3828) ||
                (device.vendorId == 0x0329 && device.productId == 0x2022)

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

    // ---------- セッション ----------

    private class Endpoints(
        val videoIface: UsbInterface,
        val videoIn: UsbEndpoint,
        val videoOut: UsbEndpoint,
        val iapIface: UsbInterface?,
        val iapIn: UsbEndpoint?,
        val iapOut: UsbEndpoint?
    )

    private var lastVideoOut: UsbEndpoint? = null

    private fun runSession(device: UsbDevice) {
        var conn: UsbDeviceConnection? = null
        var stopReason = "切断されました"
        try {
            conn = usbManager.openDevice(device)
            if (conn == null) {
                stopReason = "USB接続を開けませんでした"
                return
            }
            connection = conn

            val eps = discoverEndpoints(device) ?: run {
                stopReason = "映像インターフェースが見つかりません"
                return
            }

            if (!conn.claimInterface(eps.videoIface, true)) {
                stopReason = "映像インターフェースを掴めませんでした"
                return
            }
            try {
                conn.setInterface(eps.videoIface)
            } catch (e: Exception) {
                log("setInterface失敗 (無視): ${e.message}")
            }

            // 第1候補: iAP ハンドシェイク → BB AA 05 (実機で確認済みの本命)
            if (tryIapHandshake(conn, eps)) {
                sendBulk(conn, eps.videoOut, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00))
                if (probe(conn, eps.videoIn, 4000)) {
                    startStreaming(conn, eps)
                    return
                }
            }

            // 第2候補: ハンドシェイクなしで BB AA 05
            log("方式2: BB AA 05 00 00 を送信 (ハンドシェイクなし)")
            sendBulk(conn, eps.videoOut, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00))
            if (probe(conn, eps.videoIn, 3000)) {
                startStreaming(conn, eps)
                return
            }

            // 第3候補: コントロール転送で開始 (YUYV 個体向け)
            log("方式3: コントロール転送で開始を試行")
            val infoBuf = ByteArray(512)
            val infoN = try {
                conn.controlTransfer(0xA0, 0x00, 0x0005, 0x0000, infoBuf, 512, 1000)
            } catch (e: Exception) {
                -1
            }
            if (infoN > 0) {
                val text = String(infoBuf, 0, infoN, Charsets.UTF_8)
                    .replace(Regex("[\\x00-\\x1F]+"), " ").trim()
                log("デバイス情報応答: ${text.take(80)}")
            } else {
                log("デバイス情報応答なし ($infoN)")
            }
            val startData = ByteArray(64) { 0x30 }
            val startN = try {
                conn.controlTransfer(0x20, 0x01, 0x0005, 0x0000, startData, 64, 1000)
            } catch (e: Exception) {
                -1
            }
            log("開始コントロール転送: $startN")
            if (probe(conn, eps.videoIn, 4000)) {
                startStreaming(conn, eps)
                return
            }

            stopReason = "全方式で映像データを受信できませんでした (受信合計 $totalReadBytes バイト)"
            log(stopReason)
        } catch (e: Exception) {
            stopReason = "例外: ${e.message}"
            log(stopReason)
        } finally {
            try {
                val c = conn
                val outEp = lastVideoOut
                if (c != null && outEp != null) {
                    c.bulkTransfer(
                        outEp,
                        byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x08, 0x00, 0x00), 5, 500
                    )
                }
            } catch (e: Exception) {
                // 片付け中の失敗は無視
            }
            running = false
            try {
                conn?.close()
            } catch (e: Exception) {
                // 無視
            }
            if (connection === conn) connection = null
            onStopped(stopReason)
        }
    }

    /** iAP インターフェースの挨拶を読み、プローブを返す。成功で true */
    private fun tryIapHandshake(conn: UsbDeviceConnection, eps: Endpoints): Boolean {
        val iapIn = eps.iapIn
        val iapOut = eps.iapOut
        val iapIface = eps.iapIface
        if (iapIn == null || iapOut == null || iapIface == null) {
            log("iAPインターフェースなし (スキップ)")
            return false
        }
        log("方式1: iAPハンドシェイク後に開始")
        return try {
            conn.claimInterface(iapIface, true)
            val gbuf = ByteArray(512)
            val g1 = try {
                conn.bulkTransfer(iapIn, gbuf, gbuf.size, 1200)
            } catch (e: Exception) {
                -1
            }
            log("iAP 読み取り: $g1 ${if (g1 > 0) hexHead(gbuf, minOf(g1, 8)) else ""}")
            val pw = sendBulk(
                conn, iapOut,
                byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
            )
            log("iAP プローブ送信: $pw")
            pw > 0
        } catch (e: Exception) {
            log("iAPハンドシェイク例外 (無視): ${e.message}")
            false
        }
    }

    private fun discoverEndpoints(device: UsbDevice): Endpoints? {
        var videoIface: UsbInterface? = null
        var videoIn: UsbEndpoint? = null
        var videoOut: UsbEndpoint? = null
        var iapIface: UsbInterface? = null
        var iapIn: UsbEndpoint? = null
        var iapOut: UsbEndpoint? = null
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
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_OUT && iapOut == null) {
                        iapOut = ep; iapIface = intf
                    }
                    if (ep.direction == UsbConstants.USB_DIR_IN && iapIn == null) iapIn = ep
                }
            }
        }
        val vi = videoIface
        val vn = videoIn
        val vo = videoOut
        if (vi == null || vn == null || vo == null) return null
        lastVideoOut = vo
        return Endpoints(vi, vn, vo, iapIface, iapIn, iapOut)
    }

    private fun sendBulk(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray): Int {
        return try {
            conn.bulkTransfer(ep, data, data.size, 1000)
        } catch (e: Exception) {
            -1
        }
    }

    private fun hexHead(buf: ByteArray, len: Int): String =
        (0 until len).joinToString(" ") { String.format("%02X", buf[it]) }

    // ---------- データ受信と解析 ----------

    private fun readOnce(conn: UsbDeviceConnection, inEp: UsbEndpoint, timeoutMs: Int): Int {
        val buf = ByteArray(17408)
        val n = try {
            conn.bulkTransfer(inEp, buf, buf.size, timeoutMs)
        } catch (e: Exception) {
            -1
        }
        readCount++
        if (n > 0) {
            totalReadBytes += n
            if (readCount <= 6) {
                log("受信 #$readCount: $n バイト 先頭 ${hexHead(buf, minOf(n, 8))}")
            } else {
                Log.d(TAG, "read #$readCount: $n bytes")
            }
            if (rawBuf.size() < FRAME_BYTES + 1024) {
                rawBuf.write(buf, 0, minOf(n, FRAME_BYTES + 1024 - rawBuf.size()))
            }
            streamBuf.write(buf, 0, n)
            parsePackets()
        } else if (readCount <= 6) {
            log("受信 #$readCount: $n (データなし)")
        }
        return n
    }

    /** 指定時間データが来るか試す。1 バイトでも来たら true */
    private fun probe(conn: UsbDeviceConnection, inEp: UsbEndpoint, durationMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        val before = totalReadBytes
        while (running && SystemClock.elapsedRealtime() < deadline) {
            readOnce(conn, inEp, 1200)
            if (totalReadBytes > before) return true
        }
        return totalReadBytes > before
    }

    private fun startStreaming(conn: UsbDeviceConnection, eps: Endpoints) {
        val format = when {
            aabbSeen -> "MJPEG"
            looksLikeYuv() -> "YUYV"
            else -> "YUYV(推定)"
        }
        log("ストリーミング開始: 形式 $format (受信 $totalReadBytes バイト)")
        onStarted()
        if (format == "MJPEG") {
            while (running) {
                val n = readOnce(conn, eps.videoIn, 5000)
                if (n < 0 && !running) break
            }
        } else {
            yuvLoop(conn, eps.videoIn)
        }
        log("読み取り終了 (受信合計 $totalReadBytes バイト / 正常 $emittedFrames 枚 / 破棄 $droppedFrames 枚)")
    }

    private fun looksLikeYuv(): Boolean {
        val head = rawBuf.toByteArray()
        if (head.size >= 2 && head[0] == 0xDD.toByte() && head[1] == 0xCC.toByte()) return true
        return totalReadBytes > 0 && !aabbSeen
    }

    private fun yuvLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        val acc = ByteArrayOutputStream()
        acc.write(rawBuf.toByteArray())
        val chunk = ByteArray(65536)
        while (running) {
            var data = acc.toByteArray()
            var consumed = 0
            while (data.size - consumed >= 2 &&
                data[consumed] == 0xDD.toByte() && data[consumed + 1] == 0xCC.toByte()
            ) {
                if (data.size - consumed < 511) break
                consumed += 511
            }
            if (data.size - consumed >= FRAME_BYTES) {
                val frameBytes = data.copyOfRange(consumed, consumed + FRAME_BYTES)
                onYuvFrame(frameBytes)
                consumed += FRAME_BYTES
            }
            acc.reset()
            if (consumed < data.size) acc.write(data, consumed, data.size - consumed)
            if (acc.size() >= FRAME_BYTES + 511) continue

            val n = try {
                conn.bulkTransfer(inEp, chunk, chunk.size, 5000)
            } catch (e: Exception) {
                -1
            }
            if (n > 0) {
                totalReadBytes += n
                acc.write(chunk, 0, n)
            } else if (n < 0 && !running) {
                break
            }
        }
    }

    // ---------- MJPEG パケット解析 (フレーム ID 単位で組み立て) ----------

    private fun parsePackets() {
        val data = streamBuf.toByteArray()
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
                val fid = data[idx + 5].toInt() and 0xFF
                if (currentFrameId == -1) {
                    currentFrameId = fid
                } else if (fid != currentFrameId) {
                    finalizeFrame()
                    currentFrameId = fid
                }
                currentFrame.write(data, idx + 12, total - 12)
                if (currentFrame.size() > 262144) {
                    // 異常に大きい = 組み立て異常。捨てて立て直す
                    currentFrame.reset()
                    droppedFrames++
                }
            }
            pos = idx + total
            keepFrom = pos
        }
        streamBuf.reset()
        if (keepFrom < data.size) {
            streamBuf.write(data, keepFrom, data.size - keepFrom)
        }
    }

    /** 1 フレーム分が揃ったら検証して渡す。欠けていれば表示せず捨てる */
    private fun finalizeFrame() {
        val fb = currentFrame.toByteArray()
        currentFrame.reset()
        if (fb.size < 4) return
        val soi = indexOfMarker(fb, 0, 0xD8)
        if (soi < 0) {
            droppedFrames++
            return
        }
        val eoi = indexOfMarker(fb, soi + 2, 0xD9)
        if (eoi < 0) {
            droppedFrames++
            if (droppedFrames <= 3) {
                log("不完全なフレームを破棄 (${fb.size} バイト)")
            }
            return
        }
        emittedFrames++
        onJpegFrame(fb.copyOfRange(soi, eoi + 2))
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
