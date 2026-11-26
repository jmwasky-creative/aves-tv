package com.avestv.tv

import com.avestv.protocol.AlbumStore
import com.avestv.protocol.DiscoveryProtocol
import com.avestv.protocol.StreamingHttpServer
import java.io.Closeable

class TvHttpServer(
    port: Int = DiscoveryProtocol.HTTP_DEFAULT_PORT,
    store: AlbumStore,
    onMutation: () -> Unit,
) : Closeable {

    private val engine = StreamingHttpServer(
        port = port,
        store = store,
        onMutation = onMutation,
    )

    val boundPort: Int
        get() = engine.boundPort

    fun start() = engine.start()

    override fun close() = engine.close()
}
