package org.fossify.gallery.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelegramApiException(message: String, val code: Int, val retryAfter: Int = 0) : IOException(message)

/** Minimal Telegram Bot API client (no extra dependencies). */
class TelegramApi(private val token: String, private val chatId: String) {
    class Uploaded(val messageId: Long, val fileId: String)
    class Pinned(val messageId: Long, val fileId: String?)

    companion object {
        private const val BASE = "https://api.telegram.org"
        private const val MAX_ATTEMPTS = 5
        const val MAX_DOWNLOAD = 20L * 1024 * 1024
    }

    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TelegramApiException) {
                attempt++
                if (e.code != 429 && e.code < 500 || attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep((if (e.retryAfter > 0) e.retryAfter else attempt * 2) * 1000L + 500)
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep(attempt * 2000L)
            }
        }
    }

    private fun parse(code: Int, body: String): JsonObject {
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw TelegramApiException("Unexpected response ($code)", code)
        }
        if (!json.get("ok").asBoolean) {
            val retry = json.getAsJsonObject("parameters")?.get("retry_after")?.asInt ?: 0
            val desc = json.get("description")?.asString ?: "error"
            throw TelegramApiException(desc, json.get("error_code")?.asInt ?: code, retry)
        }
        return json
    }

    private fun readAll(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun call(method: String, vararg fields: Pair<String, String>): JsonObject = withRetry {
        val body = fields.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }.toByteArray()
        val conn = URL("$BASE/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body) }
            parse(conn.responseCode, readAll(conn))
        } finally {
            conn.disconnect()
        }
    }

    fun getMe(): JsonObject = call("getMe").getAsJsonObject("result")

    fun getChat(): JsonObject = call("getChat", "chat_id" to chatId).getAsJsonObject("result")

    fun getChatMember(userId: Long): JsonObject =
        call("getChatMember", "chat_id" to chatId, "user_id" to userId.toString()).getAsJsonObject("result")

    fun sendDocument(fileName: String, bytes: ByteArray): Uploaded = withRetry {
        val boundary = "----gc" + CloudCrypto.randomId()
        val head = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            head.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("chat_id", chatId)
        field("disable_notification", "true")
        field("protect_content", "true")
        head.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        )
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val conn = URL("$BASE/bot$token/sendDocument").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setFixedLengthStreamingMode(head.size().toLong() + bytes.size + tail.size)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use {
                it.write(head.toByteArray())
                it.write(bytes)
                it.write(tail)
            }
            val result = parse(conn.responseCode, readAll(conn)).getAsJsonObject("result")
            Uploaded(
                result.get("message_id").asLong,
                result.getAsJsonObject("document").get("file_id").asString
            )
        } finally {
            conn.disconnect()
        }
    }

    fun pinMessage(messageId: Long) {
        call(
            "pinChatMessage", "chat_id" to chatId, "message_id" to messageId.toString(),
            "disable_notification" to "true"
        )
    }

    fun deleteMessage(messageId: Long) {
        call("deleteMessage", "chat_id" to chatId, "message_id" to messageId.toString())
    }

    /** The currently pinned document of the chat (our encrypted manifest), or null when nothing document-like is pinned. */
    fun getPinnedDocument(): Pinned? {
        val pinned = getChat().getAsJsonObject("pinned_message") ?: return null
        val doc = pinned.getAsJsonObject("document") ?: return null
        return Pinned(pinned.get("message_id").asLong, doc.get("file_id").asString)
    }

    fun downloadFile(fileId: String): ByteArray {
        val path = call("getFile", "file_id" to fileId).getAsJsonObject("result").get("file_path")?.asString
            ?: throw TelegramApiException("File too big or unavailable", 400)
        return withRetry {
            val conn = URL("$BASE/file/bot$token/$path").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 120_000
                if (conn.responseCode !in 200..299) {
                    throw TelegramApiException("Download failed", conn.responseCode)
                }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
