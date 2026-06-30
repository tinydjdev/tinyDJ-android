package com.tinydj.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.io.File

/**
 * Holds the user's loaded tracks. URIs are persisted via Storage Access Framework
 * persistable permissions (so picks survive reboots); metadata is re-extracted on
 * launch. Lightweight SharedPreferences persistence is plenty for personal use —
 * swap in Room if the library grows.
 */
class LibraryRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    private val initJob = scope.launch { reloadPersisted() }

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

    private fun persist() {
        prefs.edit().putStringSet(KEY_URIS, _tracks.value.map { it.uriString }.toSet()).apply()
    }

    private fun reloadPersisted() {
        val uris = prefs.getStringSet(KEY_URIS, emptySet()).orEmpty()
        val held = appContext.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        val restored = uris.mapNotNull { s ->
            val uri = Uri.parse(s)
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return@mapNotNull null)
                if (!file.exists()) return@mapNotNull null
            } else {
                // Drop any URI whose permission was revoked out from under us.
                val hasPermission = s in held || held.any { s.startsWith(it) }
                if (!hasPermission) return@mapNotNull null
            }
            runCatching { MetadataExtractor.extract(appContext, uri) }.getOrNull()
        }
        _tracks.value = restored
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
        }
        return null
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val PREFS = "tinydj_library"
        const val KEY_URIS = "uris"
    }
}
