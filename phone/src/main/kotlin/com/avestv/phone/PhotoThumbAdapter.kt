package com.avestv.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.avestv.protocol.PhotoEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoThumbAdapter(
    private val scope: CoroutineScope,
    private val apiClient: TvApiClient,
    private val onDeleteClick: (PhotoEntry) -> Unit,
) : RecyclerView.Adapter<PhotoThumbAdapter.PhotoVH>() {

    private var currentTv: DiscoveredTv? = null
    private var items: List<PhotoEntry> = emptyList()

    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun submit(tv: DiscoveredTv?, photos: List<PhotoEntry>) {
        if (currentTv?.info?.deviceId != tv?.info?.deviceId) {
            thumbCache.evictAll()
        }
        currentTv = tv
        items = photos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_photo, parent, false)
        return PhotoVH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PhotoVH, position: Int) {
        holder.bind(items[position])
    }

    inner class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        private val tvPhotoName: TextView = view.findViewById(R.id.tvPhotoName)
        private val btnDelete: TextView = view.findViewById(R.id.btnDeletePhoto)
        private var loadJob: Job? = null

        fun bind(entry: PhotoEntry) {
            tvPhotoName.text = entry.name
            btnDelete.setOnClickListener { onDeleteClick(entry) }
            loadJob?.cancel()

            val tv = currentTv ?: return
            val cacheKey = "${tv.info.deviceId}:${entry.id}:${entry.dateModifiedSec}"
            val cached = thumbCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                ivThumb.setImageBitmap(cached)
                return
            }

            ivThumb.setImageDrawable(null)
            loadJob = scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val bytes = apiClient.fetchThumbnailBytes(tv, entry.id) ?: return@withContext null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null) {
                    thumbCache.put(cacheKey, bmp)
                    ivThumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
