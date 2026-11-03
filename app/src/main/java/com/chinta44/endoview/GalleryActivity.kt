package com.chinta44.endoview

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chinta44.endoview.databinding.ActivityGalleryBinding
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val dateSec: Long
)

/**
 * EndoView で撮った写真・動画の一覧 (アプリ内ギャラリー)。
 * ファイル名が ENDO_ で始まるものを MediaStore 全体から集めるので、
 * 保存先を変えても過去の分まで全部出る。フォルダ指定 (SAF) の場合は
 * そのフォルダの中も探す。
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val items = mutableListOf<GalleryItem>()
    private lateinit var adapter: GalleryAdapter
    private val thumbCache = HashMap<Uri, Bitmap>()
    private val executor = Executors.newFixedThreadPool(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "ギャラリー"
        adapter = GalleryAdapter()
        binding.galleryGrid.adapter = adapter
        binding.galleryGrid.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            startActivity(
                Intent(this, ViewerActivity::class.java).apply {
                    putExtra(ViewerActivity.EXTRA_URI, item.uri.toString())
                    putExtra(ViewerActivity.EXTRA_IS_VIDEO, item.isVideo)
                    putExtra(ViewerActivity.EXTRA_NAME, item.name)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        thread {
            val loaded = loadItems()
            runOnUiThread {
                items.clear()
                items.addAll(loaded)
                adapter.notifyDataSetChanged()
                binding.emptyText.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadItems(): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        result += queryMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        result += queryMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        result += querySafFolder()
        return result.sortedByDescending { it.dateSec }
    }

    private fun queryMediaStore(collection: Uri, isVideo: Boolean): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        try {
            contentResolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("ENDO_%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    list += GalleryItem(uri, isVideo, cursor.getString(nameCol) ?: "", cursor.getLong(dateCol))
                }
            }
        } catch (e: Exception) {
            // 読めない保存先はスキップ
        }
        return list
    }

    private fun querySafFolder(): List<GalleryItem> {
        val prefs = getSharedPreferences("endoview", MODE_PRIVATE)
        if (prefs.getString("save_dest", "endoview") != "saf") return emptyList()
        val treeStr = prefs.getString("save_tree", null) ?: return emptyList()
        val list = mutableListOf<GalleryItem>()
        try {
            val treeUri = Uri.parse(treeStr)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: continue
                    if (!name.startsWith("ENDO_")) continue
                    val isVideo = mime.startsWith("video/")
                    if (!isVideo && !mime.startsWith("image/")) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idCol)
                    )
                    list += GalleryItem(uri, isVideo, name, cursor.getLong(dateCol) / 1000)
                }
            }
        } catch (e: Exception) {
            // フォルダにアクセスできない場合はスキップ
        }
        return list
    }

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): GalleryItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val container: FrameLayout
            val imageView: ImageView
            val badge: TextView
            if (convertView is FrameLayout) {
                container = convertView
                imageView = container.getChildAt(0) as ImageView
                badge = container.getChildAt(1) as TextView
            } else {
                val sizePx = resources.displayMetrics.widthPixels / 3
                container = FrameLayout(this@GalleryActivity)
                container.layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, sizePx
                )
                imageView = ImageView(this@GalleryActivity)
                imageView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                imageView.setBackgroundColor(Color.parseColor("#263238"))
                container.addView(imageView)
                badge = TextView(this@GalleryActivity)
                badge.text = "▶ 動画"
                badge.setTextColor(Color.WHITE)
                badge.textSize = 10f
                badge.setBackgroundColor(0x88000000.toInt())
                badge.setPadding(8, 3, 8, 3)
                val badgeParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                badgeParams.gravity = Gravity.BOTTOM or Gravity.START
                badge.layoutParams = badgeParams
                container.addView(badge)
            }
            badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            imageView.tag = item.uri
            val cached = synchronized(thumbCache) { thumbCache[item.uri] }
            if (cached != null) {
                imageView.setImageBitmap(cached)
            } else {
                imageView.setImageBitmap(null)
                executor.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(item.uri, Size(300, 300), null)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp != null) {
                        synchronized(thumbCache) { thumbCache[item.uri] = bmp }
                        runOnUiThread {
                            if (imageView.tag == item.uri) imageView.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return container
        }
    }
}
