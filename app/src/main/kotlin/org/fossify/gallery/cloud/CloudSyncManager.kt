package org.fossify.gallery.cloud

import android.content.Context
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import org.fossify.commons.extensions.isGif
import org.fossify.commons.extensions.isImageFast
import org.fossify.commons.extensions.isVideoFast
import org.fossify.gallery.extensions.config
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class WrongKeyException : Exception("Wrong recovery key for this chat")

/**
 * Two-way sync between local folders and an (encrypted) Telegram chat.
 *
 * - Every file is cut in parts of PART_SIZE, every part is sealed by [CloudCrypto] (own random key) and
 *   uploaded as "<random>.bin". Telegram never sees names, types, dates or real sizes.
 * - An encrypted, gzipped manifest (index of all entries -> message/file ids) is kept as the pinned
 *   message of the chat, so a fresh install only needs bot token + chat id + recovery key.
 * - Files from other devices are downloaded + decrypted into [cloudDir], which is registered as an
 *   included folder, so they show up as a normal album in the gallery.
 */
class CloudSyncManager(private val context: Context) {
    class Result(val uploaded: Int, val downloaded: Int, val failed: Int)

    companion object {
        private const val PART_SIZE = 8 * 1024 * 1024
        private const val COMMIT_EVERY = 5
        private const val AUTO_SYNC_INTERVAL = 30 * 60 * 1000L
        const val CLOUD_DIR_NAME = "cloud_gallery"
        private val running = AtomicBoolean(false)
        private val gson = Gson()

        fun isRunning() = running.get()

        fun cloudDir(context: Context) = File(context.noBackupFilesDir, CLOUD_DIR_NAME).apply { mkdirs() }

        /** Called from MainActivity; cheap no-op unless enabled and due. */
        fun autoSyncIfDue(context: Context) {
            val config = context.config
            if (!config.cloudSyncEnabled || !config.cloudAutoSync) return
            if (System.currentTimeMillis() - config.cloudLastSync < AUTO_SYNC_INTERVAL) return
            if (running.get()) return
            val app = context.applicationContext
            Thread {
                try {
                    CloudSyncManager(app).sync { }
                } catch (ignored: Exception) {
                }
            }.start()
        }
    }

    private val config = context.config
    private val secrets = CloudSecrets(context)
    private val stateFile = File(context.noBackupFilesDir, "cloud_state.json")
    private val tmpDir = File(context.noBackupFilesDir, "cloud_tmp").apply { mkdirs() }

    private class Remote(val manifest: CloudManifest, val messageId: Long?)

    fun sync(progress: (String) -> Unit): Result {
        if (!running.compareAndSet(false, true)) throw IllegalStateException("Sync already running")
        try {
            val creds = secrets.load() ?: throw IllegalStateException("Cloud sync is not set up")
            val api = TelegramApi(creds.token, creds.chatId)
            var deviceId = config.cloudDeviceId
            if (deviceId.isEmpty()) {
                deviceId = CloudCrypto.randomId()
                config.cloudDeviceId = deviceId
            }

            progress("Reading index…")
            var remote = fetchRemote(api, creds.master)
            var uploaded = 0
            var downloaded = 0
            var failed = 0

            // ---- upload ----
            val known = remote.manifest.entries.map { it.src }.toHashSet()
            val pending = collectLocalFiles().filter { srcKey(it) !in known }
            val fresh = ArrayList<CloudEntry>()
            for ((index, file) in pending.withIndex()) {
                progress("Uploading ${index + 1}/${pending.size}")
                try {
                    fresh.add(uploadFile(api, creds.master, file, deviceId))
                    uploaded++
                } catch (e: TelegramApiException) {
                    if (e.code == 401 || e.code == 403 || e.code == 404) throw e
                    failed++
                } catch (e: java.io.IOException) {
                    failed++
                }
                if (fresh.size >= COMMIT_EVERY) {
                    remote = commit(api, creds.master, fresh)
                    fresh.clear()
                }
            }
            if (fresh.isNotEmpty()) {
                remote = commit(api, creds.master, fresh)
            }

            // ---- download ----
            val state = loadState()
            val toPull = remote.manifest.entries.filter { it.dev != deviceId && it.id !in state.downloaded }
            for ((index, entry) in toPull.withIndex()) {
                progress("Downloading ${index + 1}/${toPull.size}")
                try {
                    downloadEntry(api, creds.master, entry)
                    state.downloaded.add(entry.id)
                    saveState(state)
                    downloaded++
                } catch (e: TelegramApiException) {
                    if (e.code == 401 || e.code == 403) throw e
                    failed++
                } catch (e: java.io.IOException) {
                    failed++
                } catch (e: CloudCryptoException) {
                    failed++
                }
            }

            config.addIncludedFolder(cloudDir(context).absolutePath)
            config.cloudLastSync = System.currentTimeMillis()
            return Result(uploaded, downloaded, failed)
        } finally {
            running.set(false)
        }
    }

    // ---------------------------------------------------------------- setup check

    /** Returns a human readable problem, or null when everything needed is in place. Also verifies the key. */
    fun checkSetup(token: String, chatId: String, master: ByteArray): String? {
        val api = TelegramApi(token, chatId)
        try {
            val me = api.getMe()
            val chat = api.getChat()
            val member = api.getChatMember(me.get("id").asLong)
            val status = member.get("status")?.asString
            if (status != "administrator" && status != "creator") {
                return "Bot '@${me.get("username")?.asString}' must be an admin of '${chat.get("title")?.asString}'"
            }
            val needs = listOf("can_post_messages", "can_edit_messages", "can_delete_messages", "can_pin_messages")
            val missing = needs.filter { perm ->
                member.has(perm) && !member.get(perm).asBoolean
            }
            if (missing.isNotEmpty()) {
                return "Bot admin rights missing: ${missing.joinToString { it.removePrefix("can_").replace('_', ' ') }}"
            }
            fetchRemote(api, master) // throws WrongKeyException when the chat already holds data of another key
            return null
        } catch (e: WrongKeyException) {
            return "This chat already contains data encrypted with a different recovery key"
        } catch (e: TelegramApiException) {
            return "Telegram: ${e.message}"
        } catch (e: java.io.IOException) {
            return "Network error: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- manifest

    private fun fetchRemote(api: TelegramApi, master: ByteArray): Remote {
        val pinned = api.getPinnedDocument() ?: return Remote(CloudManifest(), null)
        val blob = api.downloadFile(pinned.fileId!!)
        val opened = try {
            CloudCrypto.open(master, CloudCrypto.KIND_MANIFEST, blob)
        } catch (e: CloudCryptoException) {
            throw WrongKeyException()
        }
        val json = GZIPInputStream(opened.data.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        return Remote(gson.fromJson(json, CloudManifest::class.java), pinned.messageId)
    }

    /** Merges [fresh] into the newest remote manifest, uploads + pins it, removes the old one. */
    private fun commit(api: TelegramApi, master: ByteArray, fresh: List<CloudEntry>): Remote {
        val latest = fetchRemote(api, master)
        val ids = latest.manifest.entries.map { it.id }.toHashSet()
        val merged = CloudManifest(
            seq = latest.manifest.seq + 1,
            entries = latest.manifest.entries + fresh.filter { it.id !in ids }
        )
        val gz = java.io.ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(gson.toJson(merged).toByteArray(Charsets.UTF_8)) }
        val blob = CloudCrypto.seal(master, CloudCrypto.KIND_MANIFEST, "{}".toByteArray(), gz.toByteArray())
        val up = api.sendDocument(CloudCrypto.randomUploadName(), blob)
        try {
            api.pinMessage(up.messageId)
        } catch (e: TelegramApiException) {
            runCatching { api.deleteMessage(up.messageId) }
            throw TelegramApiException("Could not pin the index (give the bot 'pin messages' / 'edit messages' right): ${e.message}", e.code)
        }
        latest.messageId?.let { runCatching { api.deleteMessage(it) } }
        return Remote(merged, up.messageId)
    }

    // ---------------------------------------------------------------- upload

    private fun collectLocalFiles(): List<File> {
        val cloud = cloudDir(context).absolutePath
        val result = ArrayList<File>()
        for (folder in config.cloudSyncFolders) {
            val files = File(folder).listFiles() ?: continue
            for (f in files) {
                val path = f.absolutePath
                if (!f.isFile || f.length() == 0L || f.name.startsWith(".") || path.startsWith(cloud)) continue
                if (path.isImageFast() || path.isVideoFast() || path.isGif()) result.add(f)
            }
        }
        return result.sortedBy { it.lastModified() }
    }

    private fun srcKey(file: File): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHex().take(32)
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uploadFile(api: TelegramApi, master: ByteArray, file: File, deviceId: String): CloudEntry {
        val id = CloudCrypto.randomId()
        val size = file.length()
        val partCount = ((size + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)
        val mime = mimeOf(file)
        val taken = file.lastModified()
        val parts = ArrayList<CloudPart>()
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(PART_SIZE)
            for (i in 0 until partCount) {
                val len = minOf(PART_SIZE.toLong(), size - i.toLong() * PART_SIZE).toInt()
                raf.seek(i.toLong() * PART_SIZE)
                raf.readFully(buffer, 0, len)
                val header = gson.toJson(CloudFileHeader(1, id, i, partCount, file.name, mime, taken)).toByteArray()
                val blob = CloudCrypto.seal(master, CloudCrypto.KIND_FILE, header, buffer, 0, len)
                val up = api.sendDocument(CloudCrypto.randomUploadName(), blob)
                parts.add(CloudPart(up.messageId, up.fileId))
            }
        }
        return CloudEntry(id, file.name, mime, size, taken, srcKey(file), deviceId, parts)
    }

    // ---------------------------------------------------------------- download

    private fun downloadEntry(api: TelegramApi, master: ByteArray, entry: CloudEntry) {
        val tmp = File(tmpDir, entry.id)
        try {
            tmp.outputStream().use { out ->
                for ((index, part) in entry.parts.withIndex()) {
                    val opened = CloudCrypto.open(master, CloudCrypto.KIND_FILE, api.downloadFile(part.f))
                    val header = gson.fromJson(String(opened.header, Charsets.UTF_8), CloudFileHeader::class.java)
                    if (header.id != entry.id || header.i != index) {
                        throw CloudCryptoException("Part does not belong to this entry")
                    }
                    out.write(opened.data)
                }
            }
            if (tmp.length() != entry.size) throw CloudCryptoException("Size mismatch")

            val dir = cloudDir(context)
            var target = File(dir, safeName(entry))
            if (target.exists()) target = File(dir, entry.id.take(6) + "_" + target.name)
            if (target.canonicalFile.parentFile != dir.canonicalFile) throw CloudCryptoException("Bad file name")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            if (entry.taken > 0) target.setLastModified(entry.taken)
        } finally {
            tmp.delete()
        }
    }

    private fun safeName(entry: CloudEntry): String {
        val cleaned = entry.name.replace('\\', '/').substringAfterLast('/')
            .filter { it.code >= 32 }.trim().take(120)
        return cleaned.ifEmpty { entry.id.take(8) }.let { if (it.startsWith(".")) "_$it" else it }
    }

    // ---------------------------------------------------------------- state

    private fun loadState(): CloudState = try {
        stateFile.reader().use { gson.fromJson(it, CloudState::class.java) } ?: CloudState()
    } catch (e: Exception) {
        CloudState()
    }

    private fun saveState(state: CloudState) {
        stateFile.writeText(gson.toJson(state))
    }
}
