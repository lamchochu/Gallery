package org.fossify.gallery.cloud

/** One uploaded blob: Telegram message id + file id. */
data class CloudPart(val m: Long, val f: String)

/** One synced photo/video. `src` = hash of local path+size+mtime, `dev` = uploading device. */
data class CloudEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val taken: Long,
    val src: String,
    val dev: String,
    val parts: List<CloudPart>
)

/** The encrypted index, stored as pinned document in the chat. */
data class CloudManifest(
    val v: Int = 1,
    val seq: Long = 0,
    val entries: List<CloudEntry> = emptyList()
)

/** Header inside every encrypted file blob (only readable with the key). */
data class CloudFileHeader(
    val v: Int,
    val id: String,
    val i: Int,
    val n: Int,
    val name: String,
    val mime: String,
    val taken: Long
)

/** Local bookkeeping: which remote entries were already pulled onto this device. */
data class CloudState(val downloaded: MutableSet<String> = HashSet())
