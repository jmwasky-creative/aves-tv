package com.avestv.protocol

import java.io.File

/**
 * Starts only the album HTTP server and the shared web pages.
 * Phone discovery, photo picking, 4K transcoding, and the TV boot service stay in the APKs.
 */
fun main(args: Array<String>) {
    val web = locateWebRoot()
    val preferred = args.firstOrNull()?.toIntOrNull() ?: DiscoveryProtocol.HTTP_DEFAULT_PORT
    val store = PreviewAlbumStore()
    val server = try {
        StreamingHttpServer(port = preferred, store = store, staticRoot = web).also { it.start() }
    } catch (_: Exception) {
        StreamingHttpServer(port = 0, store = store, staticRoot = web).also { it.start() }
    }
    val port = server.boundPort
    println("AvesTV preview")
    println("  phone  http://127.0.0.1:$port/phone.html")
    println("  tv     http://127.0.0.1:$port/tv.html")
    println("  web    ${web.path}")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}

internal fun locateWebRoot(): File {
    val env = System.getenv("AVESTV_WEB")
    val candidates = listOfNotNull(
        env?.takeIf { it.isNotBlank() }?.let { File(it) },
        File("web"),
        File("../web"),
        File("../../web"),
    )
    return candidates.firstOrNull { File(it, "phone.html").isFile }?.canonicalFile
        ?: File("web").canonicalFile
}
