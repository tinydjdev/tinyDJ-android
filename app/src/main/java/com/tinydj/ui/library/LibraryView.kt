package com.tinydj.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinydj.core.audio.AudioTrack
import com.tinydj.ui.deck.DeckUiState
import com.tinydj.ui.deck.DeckViewModel
import com.tinydj.ui.theme.Accent
import com.tinydj.ui.theme.Body
import com.tinydj.ui.theme.Ink

enum class SortOption { NAME, ARTIST, ALBUM, DATE, MEMO, SAMPLE }

@Composable
fun LibraryView(
    state: DeckUiState,
    vm: DeckViewModel,
    onTrackSelected: (AudioTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var sortBy by remember { mutableStateOf(SortOption.NAME) }
    var searchQuery by remember { mutableStateOf("") }
    var renameTargetTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var renameNewTitle by remember { mutableStateOf("") }

    var activeTab by remember { mutableStateOf("TRACKS") } // "TRACKS" or "ARTISTS"
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            vm.scanDirectoryUri(context, treeUri)
        }
    }

    val filteredSortedTracks = remember(state.library, sortBy, searchQuery) {
        val filtered = if (searchQuery.isBlank()) {
            state.library
        } else {
            val query = searchQuery.lowercase()
            state.library.filter {
                it.title.lowercase().contains(query) ||
                        it.artist.lowercase().contains(query) ||
                        it.album.lowercase().contains(query)
            }
        }

        when (sortBy) {
            SortOption.NAME -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SortOption.ARTIST -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            SortOption.ALBUM -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album })
            SortOption.DATE -> filtered.sortedByDescending { it.lastModified }
            SortOption.MEMO -> filtered.filter { it.uriString.contains("/memos/", ignoreCase = true) }.sortedByDescending { it.lastModified }
            SortOption.SAMPLE -> filtered.filter { it.uriString.contains("/samples/", ignoreCase = true) }.sortedByDescending { it.lastModified }
        }
    }

    val uniqueArtists = remember(state.library, searchQuery) {
        state.library
            .map { it.artist }
            .distinctBy { it.lowercase() }
            .filter { searchQuery.isBlank() || it.lowercase().contains(searchQuery.lowercase()) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    val artistTrackCounts = remember(state.library) {
        state.library.groupBy { it.artist.lowercase() }.mapValues { it.value.size }
    }

    val albumsForArtist = remember(state.library, selectedArtist) {
        if (selectedArtist == null) emptyList()
        else {
            state.library
                .filter { it.artist.equals(selectedArtist, ignoreCase = true) }
                .map { it.album }
                .distinctBy { it.lowercase() }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        }
    }

    val albumTrackCounts = remember(state.library, selectedArtist) {
        if (selectedArtist == null) emptyMap()
        else {
            state.library
                .filter { it.artist.equals(selectedArtist, ignoreCase = true) }
                .groupBy { it.album.lowercase() }
                .mapValues { it.value.size }
        }
    }

    val tracksForAlbum = remember(state.library, selectedArtist, selectedAlbum) {
        if (selectedArtist == null || selectedAlbum == null) emptyList()
        else {
            state.library
                .filter { 
                    it.artist.equals(selectedArtist, ignoreCase = true) && 
                    it.album.equals(selectedAlbum, ignoreCase = true) 
                }
                .sortedWith(compareBy<AudioTrack> { it.trackNumber }.thenBy { it.title })
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Body)
                .padding(16.dp)
                .padding(bottom = 8.dp)
        ) {
            // Title Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIBRARY",
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                    if (state.isBackgroundScanning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "[SYNCING...]",
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                
                // Folder Selection & Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.musicFolderUri != null) {
                        // Rescan Button
                        Box(
                            modifier = Modifier
                                .border(1.dp, Ink, RoundedCornerShape(4.dp))
                                .clickable(enabled = !state.isScanning && !state.isBackgroundScanning) {
                                    vm.rescanLibrary(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "RESCAN",
                                color = Ink,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .border(1.dp, Ink, RoundedCornerShape(4.dp))
                            .clickable(enabled = !state.isScanning) { dirPickerLauncher.launch(null) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (state.musicFolderName != null) {
                                "FOLDER: ${state.musicFolderName.uppercase()}"
                            } else {
                                "+ SELECT MUSIC FOLDER"
                            },
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("TRACKS", "ARTISTS").forEach { tab ->
                    val active = activeTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (active) Ink else Color.Transparent,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Ink,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                activeTab = tab
                                selectedArtist = null
                                selectedAlbum = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab,
                            color = if (active) Body else Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            if (activeTab == "TRACKS" || (activeTab == "ARTISTS" && selectedArtist == null)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Ink, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = if (activeTab == "TRACKS") "Search tracks, artists, albums..." else "Search artists...",
                            color = Ink.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        cursorBrush = SolidColor(Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Sort Bar
            if (activeTab == "TRACKS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SORT BY:",
                        color = Ink.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    SortOption.values().forEach { option ->
                        val active = sortBy == option
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (active) Ink else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Ink,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable { sortBy = option }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = option.name,
                                color = if (active) Body else Ink,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Back navigation for Artist/Album
            if (activeTab == "ARTISTS" && selectedArtist != null) {
                if (selectedAlbum == null) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Ink, RoundedCornerShape(4.dp))
                            .clickable { selectedArtist = null }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "< BACK TO ARTISTS",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ARTIST: ${selectedArtist?.uppercase()}",
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Ink, RoundedCornerShape(4.dp))
                            .clickable { selectedAlbum = null }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "< BACK TO ALBUMS",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ALBUM: ${selectedAlbum?.uppercase()}",
                        color = Ink,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Ink)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Main List Content
            if (activeTab == "TRACKS") {
                if (filteredSortedTracks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No results found" else "No tracks loaded.\nTap + SELECT MUSIC FOLDER to scan and import audio.",
                            color = Ink.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredSortedTracks, key = { it.id }) { track ->
                            val isPlayingTrack = state.hasTrack && (track.id == state.title || track.uriString == state.title || track.title == state.title)
                            TrackRowItem(
                                track = track,
                                isPlaying = isPlayingTrack,
                                onClick = { onTrackSelected(track) },
                                onDelete = { vm.removeTrack(track) },
                                onRename = if (track.uriString.contains("/memos/", ignoreCase = true) || track.uriString.contains("/samples/", ignoreCase = true)) {
                                    {
                                        renameTargetTrack = track
                                        renameNewTitle = track.title
                                    }
                                } else null
                            )
                        }
                    }
                }
            } else {
                if (selectedArtist == null) {
                    if (uniqueArtists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No artists found" else "No tracks loaded.",
                                color = Ink.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uniqueArtists) { artist ->
                                ArtistRowItem(
                                    artist = artist,
                                    songCount = artistTrackCounts[artist.lowercase()] ?: 0,
                                    onClick = { selectedArtist = artist }
                                )
                            }
                        }
                    }
                } else if (selectedAlbum == null) {
                    if (albumsForArtist.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No albums found.",
                                color = Ink.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(albumsForArtist) { album ->
                                AlbumRowItem(
                                    album = album,
                                    songCount = albumTrackCounts[album.lowercase()] ?: 0,
                                    onClick = { selectedAlbum = album }
                                )
                            }
                        }
                    }
                } else {
                    if (tracksForAlbum.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tracks found.",
                                color = Ink.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(tracksForAlbum, key = { it.id }) { track ->
                                val isPlayingTrack = state.hasTrack && (track.id == state.title || track.uriString == state.title || track.title == state.title)
                                TrackRowItem(
                                    track = track,
                                    isPlaying = isPlayingTrack,
                                    onClick = { onTrackSelected(track) },
                                    onDelete = { vm.removeTrack(track) },
                                    onRename = if (track.uriString.contains("/memos/", ignoreCase = true) || track.uriString.contains("/samples/", ignoreCase = true)) {
                                        {
                                            renameTargetTrack = track
                                            renameNewTitle = track.title
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }

        // Scanner Visual Overlay Dialog
        if (state.isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Ink.copy(alpha = 0.85f))
                    .clickable(enabled = state.scanError != null) { vm.dismissScanError() } // Allow tap to close only on error
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Body, RoundedCornerShape(8.dp))
                        .border(2.dp, Ink, RoundedCornerShape(8.dp))
                        .padding(20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (state.scanError != null) "SCAN ERROR" else if (state.totalCount > 0) "ORGANIZING LIBRARY..." else "SCANNING DIRECTORY...",
                            color = if (state.scanError != null) Color.Red else Ink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Text(
                            text = "STATUS: ${if (state.scanError != null) "FAILED" else "ACTIVE"}",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )

                        Text(
                            text = if (state.totalCount > 0) "PROCESSING TRACKS:" else "CURRENT FOLDER:",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            text = state.currentScanningDir,
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = if (state.totalCount > 0) "SONGS PROCESSED: ${state.scannedCount} / ${state.totalCount}" else "SONGS IDENTIFIED: ${state.scannedCount}",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )

                        if (state.totalCount > 0) {
                            val pct = (state.scannedCount.toFloat() / state.totalCount.toFloat()).coerceIn(0f, 1f)
                            val barLength = 20
                            val filled = (pct * barLength).toInt()
                            val barText = "[" + "█".repeat(filled) + "░".repeat(barLength - filled) + "] " + "${(pct * 100).toInt()}%"
                            Text(
                                text = barText,
                                color = Accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        if (state.scanError != null) {
                            Text(
                                text = "ERROR DETAILS:\n${state.scanError}",
                                color = Color.Red,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .border(1.dp, Ink, RoundedCornerShape(4.dp))
                                    .clickable { vm.dismissScanError() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "DISMISS",
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Text(
                                text = "LATEST SONGS DISCOVERED:",
                                color = Ink.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (state.recentlyFound.isEmpty()) {
                                    Text(
                                        text = "Searching files...",
                                        color = Ink.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                } else {
                                    state.recentlyFound.forEach { filename ->
                                        Text(
                                            text = "+ $filename",
                                            color = Ink,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rename Dialog Overlay
        renameTargetTrack?.let { track ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Ink.copy(alpha = 0.85f))
                    .clickable { renameTargetTrack = null } // tap outside to dismiss
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Body, RoundedCornerShape(8.dp))
                        .border(2.dp, Ink, RoundedCornerShape(8.dp))
                        .clickable(enabled = false) {} // prevent dismissing when tapping inside
                        .padding(20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (renameTargetTrack?.uriString?.contains("/samples/", ignoreCase = true) == true) "RENAME SAMPLE" else "RENAME MEMO",
                            color = Ink,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        // Text Field for new name
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Ink, RoundedCornerShape(4.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (renameNewTitle.isEmpty()) {
                                Text(
                                    text = "Enter name...",
                                    color = Ink.copy(alpha = 0.4f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                            BasicTextField(
                                value = renameNewTitle,
                                onValueChange = { renameNewTitle = it },
                                textStyle = TextStyle(
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                ),
                                cursorBrush = SolidColor(Ink),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Buttons: CANCEL / SAVE
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Cancel
                            Box(
                                modifier = Modifier
                                    .border(1.dp, Ink, RoundedCornerShape(4.dp))
                                    .clickable { renameTargetTrack = null }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "CANCEL",
                                    color = Ink,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Save
                            Box(
                                modifier = Modifier
                                    .background(Ink, RoundedCornerShape(4.dp))
                                    .border(1.dp, Ink, RoundedCornerShape(4.dp))
                                    .clickable {
                                        val newName = renameNewTitle.trim()
                                        if (newName.isNotEmpty()) {
                                            vm.renameMemo(track, newName)
                                        }
                                        renameTargetTrack = null
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "SAVE",
                                    color = Body,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    track: AudioTrack,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play indicator
        Text(
            text = if (isPlaying) "▸" else " ",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            modifier = Modifier.padding(end = 8.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            val prefix = if (track.trackNumber > 0) "%02d. ".format(track.trackNumber) else ""
            Text(
                text = prefix + track.title,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist}  ·  ${track.album}  ·  ${track.date}",
                color = Ink.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = formatDuration(track.durationMs),
            color = Ink.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // Rename button
        if (onRename != null) {
            Text(
                text = "✎",
                color = Ink.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                modifier = Modifier
                    .clickable(onClick = onRename)
                    .padding(horizontal = 6.dp)
            )
        }

        // Delete button
        Text(
            text = "×",
            color = Ink.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(horizontal = 6.dp)
        )
    }
}

@Composable
fun ArtistRowItem(
    artist: String,
    songCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = artist,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$songCount ${if (songCount == 1) "track" else "tracks"}",
            color = Ink.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
fun AlbumRowItem(
    album: String,
    songCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = album,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$songCount ${if (songCount == 1) "track" else "tracks"}",
            color = Ink.copy(alpha = 0.55f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}
