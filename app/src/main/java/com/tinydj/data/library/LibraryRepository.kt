package com.tinydj.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.tinydj.core.audio.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the user's loaded tracks. URIs are persisted via Storage Access Framework
 * persistable permissions (so picks survive reboots); metadata is stored in a JSON
 * format to avoid re-extracting on every launch. If JSON is missing, we fall back to
 * legacy URIs list and extract metadata on startup.
 */
class LibraryRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    private val _isBackgroundScanning = MutableStateFlow(false)
    val isBackgroundScanning: StateFlow<Boolean> = _isBackgroundScanning.asStateFlow()

    private val initJob = scope.launch {
        reloadPersisted()
        // Automatically run background differential scan if folder is set
        getMusicFolderUri()?.let {
            runDifferentialScan(appContext, isBackground = true)
        }
    }

    private suspend fun ensureInitialized() {
        initJob.join()
    }

    /** Import freshly-picked SAF URIs: persist read permission, extract, add. */
    suspend fun import(uris: List<Uri>, onProgress: ((imported: Int, total: Int) -> Unit)? = null) {
        ensureInitialized()
        val added = withContext(Dispatchers.IO) {
            uris.mapIndexedNotNull { index, uri ->
                runCatching {
                    try {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (e: SecurityException) {
                        // Suppress permission-grant exceptions for descendant files in a tree.
                        Log.d(TAG, "SecurityException for descendant URI: $uri")
                    }
                    val track = MetadataExtractor.extract(appContext, uri)
                    onProgress?.invoke(index + 1, uris.size)
                    track
                }.onFailure { Log.e(TAG, "import failed for $uri", it) }.getOrNull()
            }
        }
        if (added.isEmpty()) return
        _tracks.update { current ->
            val byId = LinkedHashMap<String, AudioTrack>()
            current.forEach { byId[it.id] = it }
            added.forEach { byId[it.id] = it }
            byId.values.toList()
        }
        persist()
    }

    fun remove(track: AudioTrack) {
        _tracks.update { it.filterNot { t -> t.id == track.id } }
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(track.uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        persist()
    }

    fun clearTracks() {
        _tracks.update { emptyList() }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _tracks.value.forEach { track ->
            val obj = JSONObject()
            obj.put("id", track.id)
            obj.put("uriString", track.uriString)
            obj.put("title", track.title)
            obj.put("artist", track.artist)
            obj.put("album", track.album)
            obj.put("date", track.date)
            obj.put("lastModified", track.lastModified)
            obj.put("durationMs", track.durationMs)
            obj.put("isFlac", track.isFlac)
            obj.put("trackNumber", track.trackNumber)
            array.put(obj)
        }
        prefs.edit()
            .putString(KEY_TRACKS_JSON, array.toString())
            .putStringSet(KEY_URIS, _tracks.value.map { it.uriString }.toSet())
            .apply()
    }

    private fun reloadPersisted() {
        val jsonStr = prefs.getString(KEY_TRACKS_JSON, null)
        val restored = mutableListOf<AudioTrack>()
        if (jsonStr != null) {
            runCatching {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    restored.add(
                        AudioTrack(
                            id = obj.getString("id"),
                            uriString = obj.getString("uriString"),
                            title = obj.getString("title"),
                            artist = obj.getString("artist"),
                            album = obj.optString("album", "unknown"),
                            date = obj.optString("date", "unknown"),
                            lastModified = obj.optLong("lastModified", 0L),
                            durationMs = obj.getLong("durationMs"),
                            isFlac = obj.getBoolean("isFlac"),
                            trackNumber = obj.optInt("trackNumber", 0),
                        )
                    )
                }
            }.onFailure { Log.e(TAG, "Failed to load JSON tracks, falling back", it) }
        }

        if (restored.isEmpty()) {
            val uris = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty()
            val held = appContext.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
            uris.forEach { s ->
                val uri = Uri.parse(s)
                if (uri.scheme == "file") {
                    val file = File(uri.path ?: return@forEach)
                    if (!file.exists()) return@forEach
                } else {
                    val hasPermission = s in held || held.any { s.startsWith(it) }
                    if (!hasPermission) return@forEach
                }
                runCatching {
                    val track = MetadataExtractor.extract(appContext, uri)
                    restored.add(track)
                }.onFailure { Log.e(TAG, "Fallback import failed for $s", it) }
            }
            if (restored.isNotEmpty()) {
                _tracks.value = restored
                persist()
                return
            }
        }
        _tracks.value = restored
    }

    fun getMusicFolderUri(): String? = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
    fun getMusicFolderName(): String? = prefs.getString(KEY_MUSIC_FOLDER_NAME, null)

    fun setMusicFolder(uri: String?, name: String?) {
        prefs.edit()
            .putString(KEY_MUSIC_FOLDER_URI, uri)
            .putString(KEY_MUSIC_FOLDER_NAME, name)
            .apply()
    }

    fun renameTrack(track: AudioTrack, newTitle: String): Uri? {
        val uri = Uri.parse(track.uriString)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (file.exists()) {
                val ext = file.extension.takeIf { it.isNotEmpty() } ?: "wav"
                val newFile = File(file.parentFile, "$newTitle.$ext")
                if (file.renameTo(newFile)) {
                    val newUri = Uri.fromFile(newFile)
                    _tracks.update { current ->
                        current.map { t ->
                            if (t.id == track.id) {
                                t.copy(
                                    id = newUri.toString(),
                                    uriString = newUri.toString(),
                                    title = newTitle
                                )
                            } else {
                                t
                            }
                        }
                    }
                    persist()
                    return newUri
                }
            }
        } else if (uri.scheme == "content") {
            return runCatching {
                val newUri = DocumentsContract.renameDocument(appContext.contentResolver, uri, newTitle) ?: uri
                _tracks.update { current ->
                    current.map { t ->
                        if (t.id == track.id) {
                            t.copy(
                                id = newUri.toString(),
                                uriString = newUri.toString(),
                                title = newTitle
                            )
                        } else {
                            t
                        }
                    }
                }
                persist()
                newUri
            }.onFailure { Log.e(TAG, "Failed to rename SAF document", it) }.getOrNull()
        }
        return null
    }

    fun runDifferentialScan(context: Context, isBackground: Boolean = true) {
        val folderUriStr = getMusicFolderUri() ?: return
        val treeUri = Uri.parse(folderUriStr)
        scope.launch {
            if (isBackground) {
                _isBackgroundScanning.value = true
            }
            try {
                // Ensure we take permission for the folder
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.d(TAG, "Already have or couldn't upgrade persistable permissions for $treeUri")
                }

                val existingTracks = _tracks.value
                val existingUrisMap = existingTracks.associateBy { it.uriString }
                val scannedUris = mutableSetOf<String>()
                val newOrUpdatedTracks = mutableListOf<AudioTrack>()

                val documentId = DocumentsContract.getTreeDocumentId(treeUri)
                val queue = java.util.ArrayDeque<String>()
                queue.add(documentId)

                while (queue.isNotEmpty()) {
                    val currentDirId = queue.removeFirst()
                    val dirChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDirId)

                    try {
                        context.contentResolver.query(
                            dirChildrenUri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED
                            ),
                            null, null, null
                        )?.use { cursor ->
                            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                            while (cursor.moveToNext()) {
                                val id = cursor.getString(idIdx)
                                val mime = cursor.getString(mimeIdx)
                                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else id.substringAfterLast('/')
                                val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L

                                if (name.startsWith("._")) {
                                    continue
                                }

                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    queue.add(id)
                                } else if (mime.startsWith("audio/") || isAudioExtension(name)) {
                                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                                    val fileUriStr = fileUri.toString()
                                    scannedUris.add(fileUriStr)

                                    val existing = existingUrisMap[fileUriStr]
                                    if (existing != null && existing.lastModified == lastMod) {
                                        newOrUpdatedTracks.add(existing)
                                    } else {
                                        runCatching {
                                            val track = MetadataExtractor.extract(context, fileUri)
                                            newOrUpdatedTracks.add(track.copy(lastModified = lastMod))
                                        }.onFailure {
                                            Log.e(TAG, "Failed to extract metadata for $fileUriStr", it)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error querying child documents under dir $currentDirId", e)
                    }
                }

                // Keep existing tracks that do not belong to the selected folder (e.g. legacy/manually picked files, internal memos/samples)
                val otherTracks = existingTracks.filter { !it.uriString.startsWith(folderUriStr) }
                val mergedTracks = newOrUpdatedTracks + otherTracks

                _tracks.value = mergedTracks
                persist()
            } catch (e: Exception) {
                Log.e(TAG, "Differential scan failed", e)
            } finally {
                if (isBackground) {
                    _isBackgroundScanning.value = false
                }
            }
        }
    }

    private fun isAudioExtension(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".aac")
    }

    private fun getOrCreateSubdirectory(context: Context, treeUri: Uri, parentDocId: String, dirName: String): Uri? {
        val contentResolver = context.contentResolver
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        var foundUri: Uri? = null
        runCatching {
            contentResolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    if (name == dirName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val id = cursor.getString(idCol)
                        foundUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        break
                    }
                }
            }
        }.onFailure { Log.d(TAG, "query subdirs failed for $dirName", it) }
        if (foundUri != null) return foundUri

        return runCatching {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                dirName
            )
        }.onFailure { Log.e(TAG, "Failed to create directory $dirName", it) }.getOrNull()
    }

    data class OutputFileInfo(val uri: Uri, val outputStream: java.io.OutputStream)

    fun createWavFileStream(context: Context, isSample: Boolean, fileName: String): OutputFileInfo? {
        val folderUriStr = getMusicFolderUri()
        if (folderUriStr != null) {
            val treeUri = Uri.parse(folderUriStr)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val tinyDjDirUri = getOrCreateSubdirectory(context, treeUri, rootDocId, "TinyDJ") ?: return null
            val tinyDjDocId = DocumentsContract.getDocumentId(tinyDjDirUri)

            val subDirName = if (isSample) "samples" else "memos"
            val targetDirUri = getOrCreateSubdirectory(context, treeUri, tinyDjDocId, subDirName) ?: return null
            val targetDirDocId = DocumentsContract.getDocumentId(targetDirUri)

            val fileUri = runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    targetDirUri,
                    "audio/wav",
                    fileName
                )
            }.onFailure { Log.e(TAG, "Failed to create SAF document", it) }.getOrNull() ?: return null

            val stream = context.contentResolver.openOutputStream(fileUri) ?: return null
            return OutputFileInfo(fileUri, stream)
        } else {
            // Fallback to app-specific external files dir
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
            val subDirName = if (isSample) "samples" else "memos"
            val dir = File(baseDir, "TinyDJ/$subDirName")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            val stream = FileOutputStream(file)
            return OutputFileInfo(Uri.fromFile(file), stream)
        }
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val PREFS = "tinydj_library"
        const val KEY_URIS = "uris"
        const val KEY_TRACKS_JSON = "tracks_json"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_uri"
        const val KEY_MUSIC_FOLDER_NAME = "music_folder_name"
    }
}
