package com.vishal.riy.drive

import android.accounts.Account
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Drive REST transport for ONE account's `RiyBackup` folder.
 *
 * Secretless OAuth2 via [GoogleDriveAuth] for `com.google` accounts; the
 * access token is fetched fresh per request (platform-refreshed). All objects
 * live inside the single per-account canonical folder; every network read is
 * re-verified by [RemoteCoordinator] via SHA-256.
 *
 * Exact REST mapping (cloned from the reference transport):
 *  - folder search : GET drive/v3/files?q="name='X' and mimeType='...folder' and trashed=false"
 *  - folder create : POST drive/v3/files (JSON meta)
 *  - file lookup  : GET drive/v3/files?q="name='X' and '<fid>' in parents and trashed=false"
 *  - update        : PATCH upload/drive/v3/files/<id>?uploadType=media (raw octet-stream;
 *                    POST there is an unknown route — real-device proven)
 *  - create        : POST upload/drive/v3/files?uploadType=multipart (meta + media)
 *  - download      : GET drive/v3/files/<id>?alt=media
 *  - delete        : DELETE drive/v3/files/<id> (hard delete; trash never used)
 *
 * Whole payloads are buffered in memory both ways (riy snapshots are a few KB,
 * so no resumable/chunked upload is needed). No retry/backoff here — the
 * coordinator re-reads + verifies after every write, and failures surface as
 * false/null for the manager's pending-sync queue to replay.
 */
class GoogleDriveBackupStore(
    context: Context,
    private val account: Account,
    folderName: String = RiyDriveConfig.folder,
    persistedFolderId: String? = null,
) : RemoteBackupStore {

    private val ctx = context.applicationContext
    private val resolver = DriveFolderResolver(folderName, DriveGatewayImpl(), persistedFolderId)

    private class Resp(val code: Int, val body: ByteArray)

    private fun request(
        method: String,
        url: URL,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Resp = requestOnce(method, url, body, contentType, authRetry = true)

    /**
     * Single request with exactly one silent auth recovery: a 401/403 means
     * the cached access token is stale/rejected, so it is dropped via
     * `clearToken` and the request is retried ONCE with a freshly minted
     * token. Only a second 401/403 (revoked consent, removed account, …)
     * surfaces to the caller as a genuine re-authentication signal.
     */
    private fun requestOnce(
        method: String,
        url: URL,
        body: ByteArray?,
        contentType: String?,
        authRetry: Boolean,
    ): Resp {
        val usedToken = token()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doInput = true
            if (body != null) conn.doOutput = true
            // Fresh platform-refreshed token per request; never logged.
            conn.setRequestProperty("Authorization", "Bearer $usedToken")
            conn.setRequestProperty("Accept", "*/*")
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                conn.outputStream.use { it.write(body); it.flush() }
            }
            val code = conn.responseCode
            if ((code == 401 || code == 403) && authRetry) {
                // Stale/rejected token: invalidate the ONE token that failed
                // and retry a single time with a fresh one.
                GoogleDriveAuth.invalidateToken(ctx, usedToken)
                return requestOnce(method, url, body, contentType, authRetry = false)
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            return Resp(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun token(): String = GoogleDriveAuth.accessToken(ctx, account.name)

    private fun qEnc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun fileUrl(path: String, query: String = ""): URL =
        URL("https://www.googleapis.com/drive/v3/$path?$query")

    private fun uploadUrl(path: String, query: String = ""): URL =
        URL("https://www.googleapis.com/upload/drive/v3/$path?$query")

    private fun parse(bytes: ByteArray): JSONObject = JSONObject(String(bytes, Charsets.UTF_8))

    // ------------------------------------------------------------ folder gateway

    private inner class DriveGatewayImpl : DriveFolderGateway {
        override suspend fun findFolders(name: String): List<String> {
            val out = mutableListOf<Pair<String, String>>() // (createdTime, id)
            var pageToken: String? = null
            while (true) {
                val q = "q=" + qEnc("name='$name' and mimeType='application/vnd.google-apps.folder' and trashed=false") +
                    "&fields=" + qEnc("files(id,createdTime),nextPageToken") +
                    "&pageSize=1000&orderBy=createdTime" +
                    (pageToken?.let { "&pageToken=" + qEnc(it) } ?: "")
                val r = request("GET", fileUrl("files", q))
                if (r.code !in 200..299) throw IOException("folder search failed (${r.code})")
                val root = parse(r.body)
                val arr = root.optJSONArray("files") ?: break
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    out += (f.optString("createdTime", "") to f.optString("id", ""))
                }
                pageToken = root.optString("nextPageToken", "").ifEmpty { null } ?: break
            }
            // Deterministic order: oldest created first, id as tie-break.
            return out.filter { it.second.isNotBlank() }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .map { it.second }
        }

        override suspend fun createFolder(name: String): String {
            val meta = JSONObject()
                .put("name", name)
                .put("mimeType", "application/vnd.google-apps.folder")
                .toString().toByteArray(Charsets.UTF_8)
            val r = request("POST", fileUrl("files"), meta, "application/json; charset=UTF-8")
            if (r.code !in 200..299) throw IOException("folder create failed (${r.code})")
            return parse(r.body).getString("id")
        }

        override suspend fun isValidFolder(id: String): Boolean {
            val r = request("GET", fileUrl("files/$id", "fields=" + qEnc("id,mimeType,trashed")))
            if (r.code == 404 || r.code == 403 || r.code == 410) return false
            if (r.code !in 200..299) throw IOException("folder validation failed (${r.code})")
            val o = parse(r.body)
            return o.optString("mimeType", "") == "application/vnd.google-apps.folder" &&
                !o.optBoolean("trashed", false)
        }
    }

    // ------------------------------------------------------------ file operations

    private data class Lookup(val valid: Boolean, val file: JSONObject?)

    private fun lookupFile(name: String, folderId: String): Lookup {
        var pageToken: String? = null
        var best: JSONObject? = null
        var count = 0
        while (true) {
            val q = "q=" + qEnc("name='$name' and '$folderId' in parents and trashed=false") +
                "&fields=" + qEnc("files(id,name,size,md5Checksum),nextPageToken") +
                "&pageSize=1000" +
                (pageToken?.let { "&pageToken=" + qEnc(it) } ?: "")
            val r = request("GET", fileUrl("files", q))
            if (r.code !in 200..299) return Lookup(false, null)
            val root = parse(r.body)
            val arr = root.optJSONArray("files") ?: break
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                count++
                val id = f.optString("id", "")
                if (best == null || id < best.optString("id", "")) best = f
            }
            pageToken = root.optString("nextPageToken", "").ifEmpty { null } ?: break
        }
        if (count > 1) Log.w(TAG, "lookupFile MULTI-MATCH name=$name count=$count (deterministic smallest id picked)")
        return Lookup(true, best)
    }

    private suspend fun findByName(name: String): JSONObject? {
        val fid = resolver.locate()
        val first = lookupFile(name, fid)
        if (!first.valid) {
            resolver.reset()
            return lookupFile(name, resolver.locate()).file
        }
        return first.file
    }

    override suspend fun list(): List<RemoteObject> {
        val items = listInFolder(resolver.locate())
        if (!items.valid) {
            resolver.reset()
            return listInFolder(resolver.locate()).items
        }
        return items.items
    }

    private data class FolderList(val valid: Boolean, val items: List<RemoteObject>)

    private fun listInFolder(folderId: String): FolderList {
        val out = mutableListOf<RemoteObject>()
        var pageToken: String? = null
        while (true) {
            val q = "q=" + qEnc("'$folderId' in parents and trashed=false and mimeType != 'application/vnd.google-apps.folder'") +
                "&fields=" + qEnc("files(id,name,size,md5Checksum),nextPageToken") +
                "&pageSize=1000" +
                (pageToken?.let { "&pageToken=" + qEnc(it) } ?: "")
            val r = request("GET", fileUrl("files", q))
            if (r.code !in 200..299) return FolderList(false, out)
            val root = parse(r.body)
            val arr = root.optJSONArray("files") ?: break
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                out += RemoteObject(
                    name = f.optString("name", ""),
                    sizeBytes = f.optLong("size", 0L),
                    contentSha = f.optString("md5Checksum", "").ifEmpty { null },
                )
            }
            pageToken = root.optString("nextPageToken", "").ifEmpty { null } ?: break
        }
        return FolderList(true, out)
    }

    override suspend fun findBySha(sha: String): List<RemoteObject> {
        val fid = resolver.locate()
        return listInFolderBySha(fid, sha).items
    }

    private fun listInFolderBySha(folderId: String, sha: String): FolderList {
        val out = mutableListOf<RemoteObject>()
        var pageToken: String? = null
        while (true) {
            val q = "q=" + qEnc("'$folderId' in parents and trashed=false and name contains '$sha'") +
                "&fields=" + qEnc("files(id,name,size,md5Checksum),nextPageToken") +
                "&pageSize=1000" +
                (pageToken?.let { "&pageToken=" + qEnc(it) } ?: "")
            val r = request("GET", fileUrl("files", q))
            if (r.code !in 200..299) return FolderList(false, out)
            val root = parse(r.body)
            val arr = root.optJSONArray("files") ?: break
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                out += RemoteObject(
                    name = f.optString("name", ""),
                    sizeBytes = f.optLong("size", 0L),
                    contentSha = f.optString("md5Checksum", "").ifEmpty { null },
                )
            }
            pageToken = root.optString("nextPageToken", "").ifEmpty { null } ?: break
        }
        return FolderList(true, out)
    }

    override suspend fun exists(name: String): Boolean = findByName(name) != null

    override suspend fun folderId(): String? = resolver.locate()

    override suspend fun put(name: String, bytes: ByteArray): Boolean {
        return try {
            val existing = findByName(name)
            if (existing != null) {
                val id = existing.optString("id", "")
                val r = request(
                    "PATCH",
                    uploadUrl("files/$id", "uploadType=media"),
                    bytes,
                    "application/octet-stream",
                )
                val ok = r.code in 200..299
                Log.i(TAG, "Drive put(update) name=$name id=${id.take(8)}… code=${r.code} ok=$ok")
                ok
            } else {
                val fid = resolver.locate()
                val meta = JSONObject()
                    .put("name", name)
                    .put("parents", org.json.JSONArray().put(fid))
                    .toString().toByteArray(Charsets.UTF_8)
                val boundary = "RiyBackup${System.nanoTime()}"
                val body = multipart(boundary, meta, bytes)
                val r = request(
                    "POST",
                    uploadUrl("files", "uploadType=multipart"),
                    body,
                    "multipart/related; boundary=$boundary",
                )
                val ok = r.code in 200..299
                Log.i(TAG, "Drive put(create) name=$name code=${r.code} ok=$ok")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drive put EXCEPTION name=$name: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun multipart(boundary: String, meta: ByteArray, media: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(meta.size + media.size + 512)
        fun line(s: String) {
            out.write(s.toByteArray(Charsets.US_ASCII))
            out.write('\r'.code)
            out.write('\n'.code)
        }
        line("--$boundary")
        line("Content-Type: application/json; charset=UTF-8")
        line("")
        out.write(meta)
        line("")
        line("--$boundary")
        line("Content-Type: application/octet-stream")
        line("")
        out.write(media)
        line("")
        line("--$boundary--")
        return out.toByteArray()
    }

    override suspend fun get(name: String): ByteArray? {
        return try {
            val file = findByName(name) ?: return null
            val id = file.optString("id", "")
            val r = request("GET", fileUrl("files/$id", "alt=media"))
            if (r.code in 200..299) r.body else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun delete(name: String): Boolean {
        return try {
            val file = findByName(name) ?: return false
            val id = file.optString("id", "")
            val r = request("DELETE", fileUrl("files/$id"))
            r.code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "RiyDrive"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
