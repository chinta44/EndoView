package com.chinta44.endoview

import android.graphics.Bitmap
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

/**
 * 独自プロトコル経路用の録画器。届いたフレーム (Bitmap) をエンコーダの
 * 入力 Surface に描き、H.264 / MP4 で MediaStore 用の FD へ書き出す。
 * 音声なし。解像度は 640x480 固定 (この機種の仕様)。
 */
class VendorRecorder(
    private val onFinished: (Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var lastPtsUs = -1L

    @Volatile
    private var stopRequested = false
    private var drainThread: Thread? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun start(pfd: ParcelFileDescriptor): Boolean {
        return try {
            this.pfd = pfd
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 12)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            codec = enc
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            drainThread = thread(name = "vendor-recorder") { drainLoop() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "recorder start failed", e)
            releaseAll()
            false
        }
    }

    /** 1 フレームをエンコーダへ渡す (ドライバのスレッドから呼ばれる) */
    fun feed(bitmap: Bitmap) {
        val surface = inputSurface ?: return
        if (stopRequested) return
        var canvas: android.graphics.Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        } catch (e: Exception) {
            // このフレームは落とす
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    // 無視
                }
            }
        }
    }

    /** 停止を要求する。実際の後片付けとコールバックはドレインスレッド側で行う */
    fun stop() {
        stopRequested = true
    }

    private fun drainLoop() {
        val enc = codec ?: run {
            onFinished(false)
            return
        }
        val info = MediaCodec.BufferInfo()
        var eosSignalled = false
        var eosDeadline = 0L
        var success = false
        try {
            while (true) {
                val outIndex = try {
                    enc.dequeueOutputBuffer(info, 10000)
                } catch (e: Exception) {
                    -1
                }
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val m = muxer
                        if (m != null && !muxerStarted) {
                            trackIndex = m.addTrack(enc.outputFormat)
                            m.start()
                            muxerStarted = true
                        }
                    }
                    outIndex >= 0 -> {
                        val encoded = enc.getOutputBuffer(outIndex)
                        if (encoded != null && info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            var pts = info.presentationTimeUs
                            if (pts <= lastPtsUs) pts = lastPtsUs + 8000
                            lastPtsUs = pts
                            val writeInfo = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, pts, info.flags)
                            }
                            muxer?.writeSampleData(trackIndex, encoded, writeInfo)
                        }
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try {
                            enc.releaseOutputBuffer(outIndex, false)
                        } catch (e: Exception) {
                            // 無視
                        }
                        if (isEos) {
                            success = muxerStarted
                            break
                        }
                    }
                    else -> {
                        // TRY_AGAIN_LATER 等
                        if (stopRequested && !eosSignalled) {
                            try {
                                enc.signalEndOfInputStream()
                            } catch (e: Exception) {
                                // 無視
                            }
                            eosSignalled = true
                            eosDeadline = SystemClock.elapsedRealtime() + 3000
                        } else if (eosSignalled && SystemClock.elapsedRealtime() > eosDeadline) {
                            success = muxerStarted
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "drain failed", e)
            success = false
        } finally {
            releaseAll()
            onFinished(success)
        }
    }

    private fun releaseAll() {
        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) {
            // 無視
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            // 無視
        }
        try {
            codec?.stop()
        } catch (e: Exception) {
            // 無視
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            // 無視
        }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            // 無視
        }
        try {
            pfd?.close()
        } catch (e: Exception) {
            // 無視
        }
        muxer = null
        codec = null
        inputSurface = null
        pfd = null
    }

    companion object {
        private const val TAG = "VendorRecorder"
    }
}
