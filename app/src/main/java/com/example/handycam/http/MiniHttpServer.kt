package com.example.handycam.http

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/*
 * A deliberately tiny HTTP/1.1 server built on java.net.ServerSocket.
 *
 * It replaces Ktor + Netty so the app can run on Android 5.0+ (those libraries rely on
 * newer JDK/Android APIs). It only needs to serve HandyCam's own web UI and JSON control
 * API: one request per connection ("Connection: close"), GET/POST, JSON bodies.
 *
 * The routing DSL intentionally mirrors the Ktor one that was used before
 * (routing { get("/x") { call.respond(...) } }) so the route handlers stay unchanged.
 */

private const val TAG = "MiniHttpServer"
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 1024 * 1024

class HttpStatusCode(val value: Int, val description: String) {
    companion object {
        val OK = HttpStatusCode(200, "OK")
        val BadRequest = HttpStatusCode(400, "Bad Request")
        val NotFound = HttpStatusCode(404, "Not Found")
        val Conflict = HttpStatusCode(409, "Conflict")
        val PayloadTooLarge = HttpStatusCode(413, "Payload Too Large")
        val InternalServerError = HttpStatusCode(500, "Internal Server Error")
    }
}

class ContentType private constructor(val value: String) {
    object Text {
        val Html = ContentType("text/html; charset=utf-8")
        val Plain = ContentType("text/plain; charset=utf-8")
    }

    object Application {
        val Json = ContentType("application/json; charset=utf-8")
    }
}

private val ApiJson = Json {
    encodeDefaults = true
    isLenient = true
    ignoreUnknownKeys = true
}

class ResponseHeaders {
    internal val values = LinkedHashMap<String, String>()
    fun append(name: String, value: String) {
        values[name] = value
    }
}

class HttpResponse {
    val headers = ResponseHeaders()
}

class ApplicationCall internal constructor(
    val method: String,
    val path: String,
    private val bodyText: String
) {
    val response = HttpResponse()

    internal var status: HttpStatusCode = HttpStatusCode.OK
    internal var contentType: ContentType = ContentType.Text.Plain
    internal var bodyBytes: ByteArray = ByteArray(0)
    internal var responded = false

    fun respondText(
        text: String,
        contentType: ContentType = ContentType.Text.Plain,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        this.status = status
        this.contentType = contentType
        this.bodyBytes = text.toByteArray(Charsets.UTF_8)
        this.responded = true
    }

    fun <T> respondWith(status: HttpStatusCode, serializer: KSerializer<T>, message: T) {
        if (message is String) {
            respondText(message, ContentType.Text.Plain, status)
        } else {
            respondText(ApiJson.encodeToString(serializer, message), ContentType.Application.Json, status)
        }
    }

    inline fun <reified T> respond(message: T) =
        respondWith(HttpStatusCode.OK, serializer<T>(), message)

    inline fun <reified T> respond(status: HttpStatusCode, message: T) =
        respondWith(status, serializer<T>(), message)

    fun <T> receiveWith(serializer: KSerializer<T>): T =
        ApiJson.decodeFromString(serializer, bodyText)

    inline fun <reified T> receive(): T = receiveWith(serializer<T>())
}

class RouteContext internal constructor(val call: ApplicationCall)

typealias RouteHandler = suspend RouteContext.() -> Unit

class Routing {
    internal val routes = HashMap<String, RouteHandler>()

    fun get(path: String, handler: RouteHandler) {
        routes["GET $path"] = handler
    }

    fun post(path: String, handler: RouteHandler) {
        routes["POST $path"] = handler
    }
}

fun routing(block: Routing.() -> Unit): Routing = Routing().apply(block)

class MiniHttpServer(
    private val port: Int,
    private val routing: Routing,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /** Binds the port and starts accepting connections. Throws if the port is unavailable. */
    fun start() {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            try { server.close() } catch (_: IOException) {}
            throw e
        }
        serverSocket = server
        running = true
        acceptThread = Thread({ acceptLoop(server) }, "MiniHttpServer-accept").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            scope.launch(Dispatchers.IO) {
                try {
                    handle(socket)
                } catch (e: Exception) {
                    Log.w(TAG, "request failed", e)
                } finally {
                    try { socket.close() } catch (_: IOException) {}
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        val header = readHeaderBlock(input) ?: return
        val lines = header.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return
        if (requestLine.size < 2) return
        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')

        var contentLength = 0
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0 && lines[i].substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                contentLength = lines[i].substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > MAX_BODY_BYTES) {
            writeResponse(output, HttpStatusCode.PayloadTooLarge, ContentType.Text.Plain,
                "Request body too large".toByteArray(), emptyMap())
            return
        }
        val body = if (contentLength > 0) readBody(input, contentLength) else ""

        val handler = routing.routes["$method $path"]
        if (handler == null) {
            writeResponse(output, HttpStatusCode.NotFound, ContentType.Text.Plain,
                "Not found".toByteArray(), emptyMap())
            return
        }

        val call = ApplicationCall(method, path, body)
        try {
            handler(RouteContext(call))
        } catch (e: Exception) {
            Log.e(TAG, "Handler error for $method $path", e)
            call.respondText("Internal error: ${e.message}", ContentType.Text.Plain,
                HttpStatusCode.InternalServerError)
        }
        writeResponse(output, call.status, call.contentType, call.bodyBytes, call.response.headers.values)
    }

    private fun readHeaderBlock(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        // 0 = nothing, 1 = "\r", 2 = "\r\n", 3 = "\r\n\r", 4 = "\r\n\r\n"
        var state = 0
        while (buffer.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            buffer.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return buffer.toString("ISO-8859-1")
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun writeResponse(
        output: java.io.OutputStream,
        status: HttpStatusCode,
        contentType: ContentType,
        body: ByteArray,
        extraHeaders: Map<String, String>
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status.value).append(' ').append(status.description).append("\r\n")
        head.append("Content-Type: ").append(contentType.value).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        for ((name, value) in extraHeaders) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }
}
