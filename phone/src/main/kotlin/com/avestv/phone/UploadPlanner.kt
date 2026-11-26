package com.avestv.phone

import com.avestv.protocol.UploadMode

/**
 * The first photo of a replace batch clears the TV album. Later photos append,
 * so a failed first item does not wipe the album before anything new lands.
 */
internal object UploadPlanner {
    fun modeFor(replaceWholeAlbum: Boolean, index: Int): UploadMode =
        if (replaceWholeAlbum && index == 0) UploadMode.REPLACE else UploadMode.APPEND
}
