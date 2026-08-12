package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.guillotine.desktop.media.DesktopMediaDecoder
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral400
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral500
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral800
import com.hereliesaz.guillotine.desktop.ui.theme.Neutral900
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.desktop.ui.theme.White
import com.hereliesaz.guillotine.model.MediaKind

/**
 * Every media item ever imported into the project — not just what's currently placed on the
 * timeline. See the Android `MediaBinScreen`'s doc for the full rationale; this is its desktop
 * mirror, built on `DesktopMediaDecoder.thumbnail` instead of `MediaPreview.thumbnail` and a fixed-
 * size `Dialog` (this app's usual desktop dialog shape) instead of a full-screen one.
 */
@Composable
fun DesktopMediaBinScreen(vm: EditorViewModel, onDismiss: () -> Unit) {
    val state by vm.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<MediaKind?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }

    val allTags = remember(state.document.mediaItems) {
        state.document.mediaItems.flatMap { it.tags }.distinct().sorted()
    }
    val usedMediaIds = remember(state.document.clips) { state.document.clips.map { it.mediaId }.toHashSet() }
    val filtered = remember(state.document.mediaItems, query, kindFilter, tagFilter) {
        state.document.mediaItems.filter { m ->
            (kindFilter == null || m.kind == kindFilter) &&
                (tagFilter == null || tagFilter in m.tags) &&
                (query.isBlank() || m.name.contains(query, ignoreCase = true) || m.tags.any { it.contains(query, ignoreCase = true) })
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .size(640.dp, 600.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Neutral900)
                .padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Media bin", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Neutral400) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name or tag…", color = Neutral500, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindChip("All", kindFilter == null) { kindFilter = null }
                KindChip("Video", kindFilter == MediaKind.VIDEO) { kindFilter = MediaKind.VIDEO }
                KindChip("Audio", kindFilter == MediaKind.AUDIO) { kindFilter = MediaKind.AUDIO }
                KindChip("Image", kindFilter == MediaKind.IMAGE) { kindFilter = MediaKind.IMAGE }
            }
            if (allTags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    allTags.forEach { t ->
                        KindChip(t, tagFilter == t) { tagFilter = if (tagFilter == t) null else t }
                    }
                }
            }
            when {
                state.document.mediaItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing imported yet.", color = Neutral400, fontSize = 12.sp)
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No media matches this search.", color = Neutral400, fontSize = 12.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { m ->
                        MediaBinRow(
                            media = m,
                            inUse = m.id in usedMediaIds,
                            onAddToTimeline = { vm.addClipFromMedia(m.id) },
                            onAddTag = { tag -> vm.addMediaTag(m.id, tag) },
                            onRemoveTag = { tag -> vm.removeMediaTag(m.id, tag) },
                            onRemove = { vm.removeUnusedMedia(m.id) },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else Neutral400,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) White else Neutral800)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MediaBinRow(
    media: MediaItem,
    inUse: Boolean,
    onAddToTimeline: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var addingTag by remember(media.id) { mutableStateOf(false) }
    var tagText by remember(media.id) { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Neutral800)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Neutral900)) {
            MediaThumbnail(media.uri, media.kind)
            Icon(
                kindIcon(media.kind), contentDescription = null, tint = Neutral500,
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(media.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                "${"%.1f".format(media.durationMs / 1000f)}s" + if (inUse) " · on timeline" else "",
                color = Neutral500, fontSize = 10.sp,
            )
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                media.tags.forEach { t ->
                    Text(
                        "$t ✕",
                        color = Neutral400, fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Neutral900)
                            .clickable { onRemoveTag(t) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (addingTag) {
                    OutlinedTextField(
                        value = tagText,
                        onValueChange = { tagText = it },
                        singleLine = true,
                        modifier = Modifier.width(100.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                        placeholder = { Text("tag", fontSize = 11.sp) },
                    )
                    Text(
                        "Add", color = Color(0xFF8AB4F8), fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { onAddTag(tagText); tagText = ""; addingTag = false }
                            .padding(4.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Add, contentDescription = "Add tag", tint = Neutral500,
                        modifier = Modifier.size(14.dp).clickable { addingTag = true },
                    )
                }
            }
        }
        TextButton(onClick = onAddToTimeline) { Text("Add", color = Color(0xFF8AB4F8), fontSize = 12.sp) }
        if (!inUse) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove from project", tint = Neutral500) }
        }
    }
}

@Composable
private fun MediaThumbnail(uri: String, kind: MediaKind) {
    val thumb by produceState<ImageBitmap?>(null, uri) {
        value = DesktopMediaDecoder.thumbnail(uri, kind, atMs = 0)
    }
    thumb?.let {
        androidx.compose.foundation.Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun kindIcon(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.AUDIO -> Icons.Filled.AudioFile
    MediaKind.IMAGE -> Icons.Filled.Image
}
