package com.chinta44.endoview

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chinta44.endoview.databinding.ActivityViewerBinding
import kotlin.concurrent.thread

/** ギャラリーの拡大表示。写真は静止画、動画は再生。削除と共有ができる */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var uri: Uri? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run {
            finish()
            return
        }
        val target = Uri.parse(uriStr)
        uri = target
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        title = intent.getStringExtra(EXTRA_NAME) ?: ""

        if (isVideo) {
            binding.viewerImage.visibility = View.GONE
            binding.viewerVideo.visibility = View.VISIBLE
            val controller = MediaController(this)
            controller.setAnchorView(binding.viewerVideo)
            binding.viewerVideo.setMediaController(controller)
            binding.viewerVideo.setVideoURI(target)
            binding.viewerVideo.setOnPreparedListener { it.start() }
        } else {
            thread {
                val bmp = try {
                    contentResolver.openInputStream(target)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (bmp != null) {
                        binding.viewerImage.setImageBitmap(bmp)
                    } else {
                        toast("画像を開けませんでした")
                    }
                }
            }
        }

        binding.btnShare.setOnClickListener { share() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun share() {
        val target = uri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, target)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "共有"))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage("このファイルを削除しますか？")
            .setPositiveButton("削除") { _, _ -> delete() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun delete() {
        val target = uri ?: return
        thread {
            val ok = try {
                if (target.authority == "media") {
                    contentResolver.delete(target, null, null) > 0
                } else {
                    DocumentsContract.deleteDocument(contentResolver, target)
                }
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    toast("削除しました")
                    finish()
                } else {
                    toast("削除できませんでした")
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_NAME = "name"
    }
}
