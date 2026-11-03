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
 * ファームウェア違いで開始手順が異なる個体があるため、接続時に段階的に試す:
 *  方式A: 映像IFへ BB AA 05 00 00 → AA BB ヘッダ付きパケットで JPEG 配信 (MJPEG)
 *  方式B: コントロール転送2回 (情報取得 + 開始) → YUYV422 生フレーム配信
 *  方式C: iAP インターフェースの MFi プローブ (読み→書き→読み) の後に方式A
 * データが流れた方式でそのままストリーミングを続ける。
 *
 * MJPEG 形式: IN パケットは 12 バイトヘッダ (AA BB / CID / 長さLE / フレームID /
 * フラグ) + JPEG データ。ヘッダを除いて連結し FF D8..FF D9 で切り出す。
 * ヘッダのフラグ byte[7] bit1 は本体の物理ボタン。
 * YUYV 形式: 1 フレーム = 614400 バイト (640x480x2)。間に 511 バイトの
 * DD CC 始まりステータスパケットが挟まることがある。
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

    // ストリーム解析用の状態 (方式判定プローブと本読み取りで共有する)
    private val streamBuf = ByteArrayOutputStream()
    private val frameBuf = ByteArrayOutputStream()
    private val rawBuf = ByteArrayOutputStream()
    private var aabbSeen = false
    private var prevButtonBit = 0
    private var totalReadBytes = 0L
    private var readCount = 0

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

            // 方式A: BB AA 05 で開始 → MJPEG
            log("方式A: BB AA 05 00 00 を送信")
            sendBulk(conn, eps.videoOut, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00))
            if (probe(conn, eps.videoIn, 4500)) {
                startStreaming(conn, eps)
                return
            }

            // 方式B: コントロール転送で開始 → YUYV (または MJPEG)
            log("方式B: コントロール転送で開始を試行")
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
            if (probe(conn, eps.videoIn, 4500)) {
                startStreaming(conn, eps)
                return
            }

            // 方式C: iAP プローブ (読み→書き→読み) の後に方式Aを再試行
            val iapIn = eps.iapIn
            val iapOut = eps.iapOut
            val iapIface = eps.iapIface
            if (iapIn != null && iapOut != null && iapIface != null) {
                log("方式C: iAPプローブ後に再試行")
                try {
                    conn.claimInterface(iapIface, true)
                    val gbuf = ByteArray(512)
                    val g1 = try {
                        conn.bulkTransfer(iapIn, gbuf, gbuf.size, 800)
                    } catch (e: Exception) {
                        -1
                    }
                    log("iAP 読み取り(前): $g1 ${if (g1 > 0) hexHead(gbuf, minOf(g1, 8)) else ""}")
                    val pw = sendBulk(
                        conn, iapOut,
                        byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
                    )
                    log("iAP プローブ送信: $pw")
                    val g2 = try {
                        conn.bulkTransfer(iapIn, gbuf, gbuf.size, 800)
                    } catch (e: Exception) {
                        -1
                    }
                    log("iAP 読み取り(後): $g2 ${if (g2 > 0) hexHead(gbuf, minOf(g2, 8)) else ""}")
                } catch (e: Exception) {
                    log("iAPプローブ例外 (無視): ${e.message}")
                }
                sendBulk(conn, eps.videoOut, byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00))
                if (probe(conn, eps.videoIn, 4500)) {
                    startStreaming(conn, eps)
                    return
                }
            }

            stopReason = "全方式で映像データを受信できませんでした (受信合計 $totalReadBytes バイト)"
            log(stopReason)
        } catch (e: Exception) {
            stopReason = "例外: ${e.message}"
            log(stopReason)
        } finally {
            try {
                val c = conn
                if (c != null) {
                    val outEp = lastVideoOut
                    if (outEp != null) {
                        c.bulkTransfer(
                            outEp,
                            byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x08, 0x00, 0x00), 5, 500
                        )
                    }
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

    private var lastVideoOut: UsbEndpoint? = null

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

    /**
     * 1 回読み取り、解析バッファへ流す。戻り値は読み取ったバイト数 (-1 はタイムアウト等)。
     */
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
            if (readCount <= 10) {
                log("受信 #$readCount: $n バイト 先頭 ${hexHead(buf, minOf(n, 8))}")
            } else {
                Log.d(TAG, "read #$readCount: $n bytes")
            }
            if (rawBuf.size() < FRAME_BYTES + 1024) {
                rawBuf.write(buf, 0, minOf(n, FRAME_BYTES + 1024 - rawBuf.size()))
            }
            streamBuf.write(buf, 0, n)
            parsePackets()
            carveJpegs()
        } else if (readCount <= 10) {
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

    /** 流れた方式で本ストリーミングに入る */
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
        log("読み取り終了 (受信合計 $totalReadBytes バイト)")
    }

    private fun looksLikeYuv(): Boolean {
        val head = rawBuf.toByteArray()
        if (head.size >= 2 && head[0] == 0xDD.toByte() && head[1] == 0xCC.toByte()) return true
        // ステータスパケットを除いた実データが十分ある場合は YUYV とみなす
        return totalReadBytes > 0 && !aabbSeen
    }

    private fun yuvLoop(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        // rawBuf の蓄積分から開始する
        val acc = ByteArrayOutputStream()
        acc.write(rawBuf.toByteArray())
        val chunk = ByteArray(65536)
        while (running) {
            // フレーム境界処理: 先頭が DD CC なら 511 バイト捨てる
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
            if (acc.size() >= FRAME_BYTES + 511) continue // まだ処理できる

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
                frameBuf.write(data, idx + 12, total - 12)
            }
            pos = idx + total
            keepFrom = pos
        }
        streamBuf.reset()
        if (keepFrom < data.size) {
            streamBuf.write(data, keepFrom, data.size - keepFrom)
        }
    }

    private fun carveJpegs() {
        var fb = frameBuf.toByteArray()
        while (true) {
            val soi = indexOfMarker(fb, 0, 0xD8)
            if (soi < 0) {
                if (fb.size > 65536) frameBuf.reset()
                return
            }
            val eoi = indexOfMarker(fb, soi + 2, 0xD9)
            if (eoi < 0) {
                if (soi > 0) {
                    frameBuf.reset()
                    frameBuf.write(fb, soi, fb.size - soi)
                }
                if (fb.size > 1_000_000) frameBuf.reset()
                return
            }
            val jpeg = fb.copyOfRange(soi, eoi + 2)
            onJpegFrame(jpeg)
            val rest = fb.size - (eoi + 2)
            frameBuf.reset()
            if (rest > 0) frameBuf.write(fb, eoi + 2, rest)
            fb = frameBuf.toByteArray()
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
