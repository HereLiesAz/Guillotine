package com.hereliesaz.guillotine.editor

import androidx.lifecycle.ViewModel
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.GlobalSettings
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EditorTool { SELECT, SPLIT, KEYFRAME, CROP }

/** Default on-timeline duration for still images. */
private const val IMAGE_DEFAULT_DURATION_MS = 5_000L
private const val MIN_CLIP_DURATION_MS = 100L
private const val HISTORY_LIMIT = 100

data class EditorUiState(
    val document: Document = Document(),
    val currentTimeMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** Timeline zoom in pixels-per-second (horizontal). */
    val pixelsPerSecond: Float = 100f,
    /** Per-track lane heights in dp (vertical zoom); absent = default. */
    val trackHeights: Map<String, Float> = emptyMap(),
    /** New keyframes get a smooth ease by default; off = linear. */
    val autoEase: Boolean = true,
    val playbackRate: Float = 1f,
    val selectedClipIds: List<String> = emptyList(),
    /** Currently-selected keyframe (shows ease handles on its segment). */
    val selectedKeyframeId: String? = null,
    val tool: EditorTool = EditorTool.SELECT,
    val isProcessing: Boolean = false,
    val error: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedClipId: String? get() = selectedClipIds.singleOrNull()
    val selectedClips: List<TimelineClip>
        get() = document.clips.filter { it.id in selectedClipIds }

    /** Lane height (dp) for [trackId], falling back to the default. */
    fun trackHeight(trackId: String): Float = trackHeights[trackId] ?: DEFAULT_TRACK_HEIGHT
}

const val DEFAULT_TRACK_HEIGHT = 64f
const val MIN_TRACK_HEIGHT = 44f
const val MAX_TRACK_HEIGHT = 240f

/**
 * Owns all editor state. Content mutations go through [mutateDocument] so undo/redo
 * stays consistent; transient view state (playhead, zoom, selection) is updated
 * directly and is not undoable.
 */
class EditorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val past = ArrayDeque<Document>()
    private val future = ArrayDeque<Document>()

    private val document: Document get() = _uiState.value.document

    // ---- undo/redo plumbing ------------------------------------------------

    private fun mutateDocument(transform: (Document) -> Document) {
        val current = document
        val next = transform(current)
        if (next === current) return
        past.addLast(current)
        if (past.size > HISTORY_LIMIT) past.removeFirst()
        future.clear()
        _uiState.update { it.copy(document = next, canUndo = past.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        if (past.isEmpty()) return
        val prev = past.removeLast()
        future.addLast(document)
        _uiState.update {
            it.copy(
                document = prev,
                selectedClipIds = it.selectedClipIds.filter { id -> prev.clips.any { c -> c.id == id } },
                canUndo = past.isNotEmpty(),
                canRedo = true,
            )
        }
    }

    fun redo() {
        if (future.isEmpty()) return
        val next = future.removeLast()
        past.addLast(document)
        _uiState.update {
            it.copy(document = next, canUndo = true, canRedo = future.isNotEmpty())
        }
    }

    // ---- media import ------------------------------------------------------

    /**
     * Add imported media and append matching clip(s) at the end of the timeline. If
     * [targetTrack] is a track of the matching type, the clips land there (e.g. importing
     * from a specific track header); otherwise they go to the default V1/A1 track.
     */
    fun addMedia(items: List<MediaItem>, targetTrack: String? = null) {
        if (items.isEmpty()) return
        mutateDocument { doc ->
            val videoTrack = targetTrack?.takeIf { it in doc.videoTracks } ?: "V1"
            val audioTrack = targetTrack?.takeIf { it in doc.audioTracks } ?: "A1"
            val newClips = mutableListOf<TimelineClip>()
            var cursor = doc.totalDurationMs
            for (m in items) {
                when (m.kind) {
                    MediaKind.VIDEO -> {
                        // One video clip; its audio is governed by the clip's volume
                        // filter (no separate auto audio clip -> no double audio).
                        newClips += videoClip(m, cursor, videoTrack)
                        cursor += m.durationMs
                    }
                    MediaKind.AUDIO -> {
                        newClips += audioClip(m, cursor, audioTrack)
                        cursor += m.durationMs
                    }
                    MediaKind.IMAGE -> {
                        val dur = if (m.durationMs > 0) m.durationMs else IMAGE_DEFAULT_DURATION_MS
                        newClips += videoClip(m.copy(durationMs = dur), cursor, videoTrack)
                        cursor += dur
                    }
                }
            }
            doc.copy(
                mediaItems = doc.mediaItems + items,
                clips = doc.clips + newClips,
            )
        }
    }

    private fun videoClip(m: MediaItem, startMs: Long, trackId: String = "V1") = TimelineClip(
        id = newId(),
        mediaId = m.id,
        type = ClipType.VIDEO,
        trackId = trackId,
        startTimeMs = startMs,
        trimStartMs = 0,
        durationMs = m.durationMs,
    )

    private fun audioClip(m: MediaItem, startMs: Long, trackId: String = "A1") = TimelineClip(
        id = newId(),
        mediaId = m.id,
        type = ClipType.AUDIO,
        trackId = trackId,
        startTimeMs = startMs,
        trimStartMs = 0,
        durationMs = m.durationMs,
    )

    // ---- clip operations ---------------------------------------------------

    fun updateClip(id: String, transform: (TimelineClip) -> TimelineClip) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { if (it.id == id) transform(it) else it })
        }
    }

    fun updateSelectedFilters(transform: (ClipFilters) -> ClipFilters) {
        val ids = _uiState.value.selectedClipIds.toSet()
        if (ids.isEmpty()) return
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { if (it.id in ids) it.copy(filters = transform(it.filters)) else it })
        }
    }

    fun setPromptForSelected(prompt: String) {
        val ids = _uiState.value.selectedClipIds.toSet()
        if (ids.isEmpty()) return
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { if (it.id in ids) it.copy(prompt = prompt) else it })
        }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedClipIds.toSet()
        if (ids.isEmpty()) return
        mutateDocument { doc -> doc.copy(clips = doc.clips.filter { it.id !in ids }) }
        _uiState.update { it.copy(selectedClipIds = emptyList()) }
    }

    /** Bind every selected clip into one group (they then select/delete/edit together). */
    fun groupSelected() {
        val ids = _uiState.value.selectedClipIds.toSet()
        if (ids.size < 2) return
        val gid = newId()
        mutateDocument { doc -> doc.copy(clips = doc.clips.map { if (it.id in ids) it.copy(groupId = gid) else it }) }
    }

    /** Clear grouping on the selected clips. */
    fun ungroupSelected() {
        val ids = _uiState.value.selectedClipIds.toSet()
        if (ids.isEmpty()) return
        mutateDocument { doc -> doc.copy(clips = doc.clips.map { if (it.id in ids && it.groupId != null) it.copy(groupId = null) else it }) }
    }

    /** Grow an id set so that selecting any grouped clip pulls in the rest of its group. */
    private fun expandGroups(doc: Document, ids: Collection<String>): List<String> {
        val groupIds = doc.clips.filter { it.id in ids }.mapNotNull { it.groupId }.toSet()
        if (groupIds.isEmpty()) return ids.toList()
        val expanded = LinkedHashSet(ids)
        doc.clips.forEach { if (it.groupId != null && it.groupId in groupIds) expanded.add(it.id) }
        return expanded.toList()
    }

    /**
     * Split [clipId] at the current playhead. Keyframes are partitioned and the
     * second half's keyframe times are re-based; edits are source-absolute and so
     * remain valid for both halves (clamped to each half's trim window at render).
     */
    fun splitSelectedAtPlayhead() {
        val id = _uiState.value.selectedClipId ?: return
        splitClip(id, _uiState.value.currentTimeMs)
    }

    fun splitClip(clipId: String, timelineMs: Long) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val clipTimeMs = timelineMs - clip.startTimeMs
            if (clipTimeMs <= 0 || clipTimeMs >= clip.durationMs) return@mutateDocument doc

            val first = clip.copy(
                durationMs = clipTimeMs,
                keyframes = clip.keyframes.filter { it.timeMs <= clipTimeMs },
            )
            val second = clip.copy(
                id = newId(),
                startTimeMs = clip.startTimeMs + clipTimeMs,
                trimStartMs = clip.trimStartMs + clipTimeMs,
                durationMs = clip.durationMs - clipTimeMs,
                keyframes = clip.keyframes
                    .filter { it.timeMs > clipTimeMs }
                    .map { it.copy(id = newId(), timeMs = it.timeMs - clipTimeMs) },
            )
            doc.copy(clips = doc.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) })
        }
    }

    /**
     * Segmentation: split the selected clip into discrete clips at every AI/edit segment
     * boundary, keeping all pieces in place. Each piece becomes an independent clip (its
     * keyframes partitioned/re-based, edit marks cleared) so the user can rearrange or
     * delete the unwanted ones — complementing non-destructive clip-cutting (keep/remove
     * ranges applied at export).
     */
    fun segmentSelectedClip() {
        val clip = _uiState.value.selectedClips.singleOrNull() ?: return
        if (clip.edits.isEmpty()) return
        // Boundaries in clip-relative ms, deduped and sorted.
        val bounds = sortedSetOf(0L, clip.durationMs)
        clip.edits.forEach { e ->
            bounds.add((e.startMs - clip.trimStartMs).coerceIn(0, clip.durationMs))
            bounds.add((e.endMs - clip.trimStartMs).coerceIn(0, clip.durationMs))
        }
        val cuts = bounds.toList()
        if (cuts.size <= 2) return // no internal boundary → nothing to segment

        mutateDocument { doc ->
            val pieces = mutableListOf<TimelineClip>()
            for (i in 0 until cuts.size - 1) {
                val relStart = cuts[i]
                val relEnd = cuts[i + 1]
                val dur = relEnd - relStart
                if (dur < MIN_CLIP_DURATION_MS) continue
                pieces += clip.copy(
                    id = newId(),
                    startTimeMs = clip.startTimeMs + relStart,
                    trimStartMs = clip.trimStartMs + relStart,
                    durationMs = dur,
                    edits = emptyList(),
                    keyframes = clip.keyframes
                        .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                        .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                )
            }
            if (pieces.isEmpty()) return@mutateDocument doc
            doc.copy(clips = doc.clips.flatMap { if (it.id == clip.id) pieces else listOf(it) })
        }
        _uiState.update { it.copy(selectedClipIds = emptyList()) }
    }

    /** Move a clip to another track + position, validating track/clip type match. */
    fun moveClip(clipId: String, targetTrackId: String, newStartMs: Long) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val isVideoTrack = targetTrackId in doc.videoTracks
            val isAudioTrack = targetTrackId in doc.audioTracks
            // Text and video clips both live on video tracks.
            val compatible = ((clip.type == ClipType.VIDEO || clip.type == ClipType.TEXT) && isVideoTrack) ||
                (clip.type == ClipType.AUDIO && isAudioTrack)
            if (!compatible) return@mutateDocument doc
            doc.copy(
                clips = doc.clips.map {
                    if (it.id == clipId) it.copy(trackId = targetTrackId, startTimeMs = newStartMs.coerceAtLeast(0))
                    else it
                },
            )
        }
    }

    /**
     * Drag the LEFT edge: shift the clip's start on both the timeline and within the
     * source by [deltaMs], shortening the clip. Keyframes (clip-relative) are re-based
     * and any that fall before the new start are dropped. Bounded so trimStart >= 0 and
     * the clip keeps a minimum duration.
     */
    fun trimClipStart(clipId: String, deltaMs: Long) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            var d = deltaMs.coerceAtLeast(-clip.trimStartMs)
            d = d.coerceAtMost(clip.durationMs - MIN_CLIP_DURATION_MS)
            if (d == 0L) return@mutateDocument doc
            doc.copy(clips = doc.clips.map {
                if (it.id != clipId) it
                else it.copy(
                    startTimeMs = (it.startTimeMs + d).coerceAtLeast(0),
                    trimStartMs = it.trimStartMs + d,
                    durationMs = it.durationMs - d,
                    keyframes = it.keyframes.map { k -> k.copy(timeMs = k.timeMs - d) }.filter { k -> k.timeMs >= 0 },
                )
            })
        }
    }

    /**
     * Drag the RIGHT edge: change the clip's duration by [deltaMs]. Bounded to a
     * minimum duration and, for time-based media, to the remaining source length.
     */
    fun trimClipEnd(clipId: String, deltaMs: Long) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            var d = deltaMs.coerceAtLeast(MIN_CLIP_DURATION_MS - clip.durationMs)
            val media = doc.mediaFor(clip)
            if (media != null && media.kind != com.hereliesaz.guillotine.model.MediaKind.IMAGE && media.durationMs > 0) {
                val maxDuration = media.durationMs - clip.trimStartMs
                d = d.coerceAtMost(maxDuration - clip.durationMs)
            }
            if (d == 0L) return@mutateDocument doc
            val newDuration = clip.durationMs + d
            doc.copy(clips = doc.clips.map {
                if (it.id != clipId) it
                else it.copy(durationMs = newDuration, keyframes = it.keyframes.filter { k -> k.timeMs <= newDuration })
            })
        }
    }

    fun addTrack(type: ClipType) {
        mutateDocument { doc ->
            when (type) {
                // Text lives on video tracks, so "add track" for a text selection adds a video track.
                ClipType.VIDEO, ClipType.TEXT -> doc.copy(videoTracks = doc.videoTracks + "V${doc.videoTracks.size + 1}")
                ClipType.AUDIO -> doc.copy(audioTracks = doc.audioTracks + "A${doc.audioTracks.size + 1}")
            }
        }
    }

    /** Edit the caption text of a [ClipType.TEXT] clip. */
    fun setClipText(clipId: String, text: String) = updateClip(clipId) { it.copy(text = text) }

    /** Set the typeface of a [ClipType.TEXT] clip. */
    fun setClipFont(clipId: String, font: com.hereliesaz.guillotine.model.TextFont) =
        updateClip(clipId) { it.copy(font = font) }

    // ---- whole-track settings ----------------------------------------------

    fun updateTrackSettings(trackId: String, transform: (com.hereliesaz.guillotine.model.TrackSettings) -> com.hereliesaz.guillotine.model.TrackSettings) {
        mutateDocument { doc ->
            doc.copy(trackSettings = doc.trackSettings + (trackId to transform(doc.trackSettingsFor(trackId))))
        }
    }

    fun toggleTrackMuted(trackId: String) = updateTrackSettings(trackId) { it.copy(muted = !it.muted) }
    fun toggleTrackDisabled(trackId: String) = updateTrackSettings(trackId) { it.copy(disabled = !it.disabled) }
    fun setTrackVolume(trackId: String, volume: Float) = updateTrackSettings(trackId) { it.copy(volume = volume) }
    fun setTrackOpacity(trackId: String, opacity: Float) = updateTrackSettings(trackId) { it.copy(opacity = opacity) }

    /** Create an empty caption/text clip on [trackId] at the playhead, ready to edit. */
    fun addEmptyTextClip(trackId: String) {
        mutateDocument { doc ->
            doc.copy(
                clips = doc.clips + TimelineClip(
                    id = newId(),
                    mediaId = "",
                    type = ClipType.TEXT,
                    trackId = trackId,
                    startTimeMs = _uiState.value.currentTimeMs,
                    trimStartMs = 0,
                    durationMs = 3_000,
                    text = "Text",
                ),
            )
        }
    }

    /**
     * Turn a transcript of [sourceClipId]'s media into text/caption clips on the text track
     * above the video, and group them with the source clip so they select/delete together.
     * Cue times are source-media ms; only the part inside the clip's trimmed window is used.
     */
    fun addTextClipsFromTranscript(sourceClipId: String, cues: List<com.hereliesaz.guillotine.ai.TranscriptCue>) {
        if (cues.isEmpty()) return
        mutateDocument { doc ->
            val source = doc.clips.firstOrNull { it.id == sourceClipId } ?: return@mutateDocument doc
            // Caption clips go on the top video track (above the source), like any overlay clip.
            val docWithTrack = if (doc.videoTracks.isEmpty()) doc.copy(videoTracks = listOf("V1")) else doc
            val track = docWithTrack.videoTracks.first()
            val gid = source.groupId ?: newId()
            val clipStartSrc = source.trimStartMs
            val clipEndSrc = source.trimStartMs + source.durationMs

            val textClips = cues.mapNotNull { cue ->
                val s = cue.startMs.coerceIn(clipStartSrc, clipEndSrc)
                val e = cue.endMs.coerceIn(clipStartSrc, clipEndSrc)
                if (e <= s || cue.text.isBlank()) return@mapNotNull null
                TimelineClip(
                    id = newId(),
                    mediaId = "",
                    type = ClipType.TEXT,
                    trackId = track,
                    startTimeMs = source.startTimeMs + (s - clipStartSrc),
                    trimStartMs = 0,
                    durationMs = e - s,
                    text = cue.text.trim(),
                    groupId = gid,
                )
            }
            if (textClips.isEmpty()) return@mutateDocument doc
            val withGroup = docWithTrack.clips.map { if (it.id == sourceClipId) it.copy(groupId = gid) else it }
            docWithTrack.copy(clips = withGroup + textClips)
        }
    }

    // ---- keyframes ---------------------------------------------------------

    fun addKeyframe(clipId: String, property: KeyframeProperty) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val rel = (_uiState.value.currentTimeMs - clip.startTimeMs).coerceIn(0, clip.durationMs)
                val default = if (property == KeyframeProperty.VOLUME) clip.filters.volume else 1f
                // Auto-ease (default) gives a smooth in/out; off = linear.
                val easing = if (_uiState.value.autoEase) {
                    com.hereliesaz.guillotine.model.CubicBezier()
                } else {
                    com.hereliesaz.guillotine.model.CubicBezier(0f, 0f, 1f, 1f)
                }
                val kf = Keyframe(id = newId(), timeMs = rel, value = default, property = property, easing = easing)
                clip.copy(keyframes = (clip.keyframes + kf).sortedBy { it.timeMs })
            })
        }
    }

    fun updateKeyframe(clipId: String, keyframeId: String, transform: (Keyframe) -> Keyframe) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) clip
                else clip.copy(keyframes = clip.keyframes.map { if (it.id == keyframeId) transform(it) else it })
            })
        }
    }

    fun selectKeyframe(id: String?) = _uiState.update { it.copy(selectedKeyframeId = id) }

    /**
     * Tap a keyframe: select it and toggle its ease (linear ↔ smooth). Tapping again flips
     * it back. The bezier handles shown while selected allow fine adjustment.
     */
    fun tapKeyframe(clipId: String, keyframeId: String) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { c ->
                if (c.id != clipId) c
                else c.copy(keyframes = c.keyframes.map { kf ->
                    if (kf.id != keyframeId) kf else kf.copy(easing = toggledEase(kf.easing))
                })
            })
        }
        _uiState.update { it.copy(selectedKeyframeId = keyframeId) }
    }

    private fun toggledEase(e: com.hereliesaz.guillotine.model.CubicBezier): com.hereliesaz.guillotine.model.CubicBezier =
        if (e.x1 == 0f && e.y1 == 0f && e.x2 == 1f && e.y2 == 1f) {
            com.hereliesaz.guillotine.model.CubicBezier() // linear -> smooth ease
        } else {
            com.hereliesaz.guillotine.model.CubicBezier(0f, 0f, 1f, 1f) // anything -> linear
        }

    fun removeKeyframe(clipId: String, keyframeId: String) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) clip
                else clip.copy(keyframes = clip.keyframes.filter { it.id != keyframeId })
            })
        }
    }

    // ---- AI / analysis results --------------------------------------------

    fun setAnalyzing(ids: Collection<String>, analyzing: Boolean) {
        val set = ids.toSet()
        _uiState.update { st ->
            st.copy(document = st.document.copy(clips = st.document.clips.map {
                if (it.id in set) it.copy(isAnalyzing = analyzing) else it
            }))
        }
    }

    fun applyEdits(clipId: String, edits: List<com.hereliesaz.guillotine.model.EditSegment>) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { if (it.id == clipId) it.copy(edits = edits, isAnalyzing = false) else it })
        }
    }

    fun setProcessing(processing: Boolean, error: String? = null) {
        _uiState.update { it.copy(isProcessing = processing, error = error) }
    }

    // ---- global settings ---------------------------------------------------

    fun setGlobalSettings(settings: GlobalSettings) {
        mutateDocument { doc -> doc.copy(settings = settings) }
    }

    // ---- clipboard ---------------------------------------------------------

    private var clipboardClip: TimelineClip? = null
    private var clipboardFilters: ClipFilters? = null

    fun copySelected() {
        clipboardClip = _uiState.value.selectedClips.singleOrNull()?.copy()
    }

    fun copySelectedFilters() {
        clipboardFilters = _uiState.value.selectedClips.singleOrNull()?.filters
    }

    fun pasteClip() {
        val src = clipboardClip ?: return
        mutateDocument { doc ->
            doc.copy(
                clips = doc.clips + src.copy(
                    id = newId(),
                    startTimeMs = _uiState.value.currentTimeMs,
                    keyframes = src.keyframes.map { it.copy(id = newId()) },
                ),
            )
        }
    }

    fun pasteFiltersToSelected() {
        val f = clipboardFilters ?: return
        updateSelectedFilters { f }
    }

    // ---- transient view state ----------------------------------------------

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0, document.totalDurationMs)
        _uiState.update { it.copy(currentTimeMs = clamped) }
    }

    /** Advance the playhead by [deltaMs]; auto-pause at the end of the timeline. */
    fun advancePlayhead(deltaMs: Long) {
        val total = document.totalDurationMs
        val next = _uiState.value.currentTimeMs + deltaMs
        if (next >= total) {
            _uiState.update { it.copy(currentTimeMs = total, isPlaying = false) }
        } else {
            _uiState.update { it.copy(currentTimeMs = next.coerceAtLeast(0)) }
        }
    }

    fun togglePlay() = _uiState.update { it.copy(isPlaying = !it.isPlaying && it.document.totalDurationMs > 0) }
    fun setPlaying(playing: Boolean) = _uiState.update { it.copy(isPlaying = playing) }
    fun setPlaybackRate(rate: Float) = _uiState.update { it.copy(playbackRate = rate) }
    fun setZoom(pxPerSec: Float) = _uiState.update { it.copy(pixelsPerSecond = pxPerSec.coerceIn(10f, 500f)) }

    /**
     * Vertical pinch: scale lane height. Affects the track(s) of the current selection so
     * tracks size independently; with nothing selected it scales every track together.
     */
    fun scaleTrackHeight(factor: Float) {
        _uiState.update { st ->
            val selectedTracks = st.selectedClips.map { it.trackId }.distinct()
            val targets = selectedTracks.ifEmpty { st.document.videoTracks + st.document.audioTracks }
            val updated = st.trackHeights.toMutableMap()
            targets.forEach { id ->
                updated[id] = (st.trackHeight(id) * factor).coerceIn(MIN_TRACK_HEIGHT, MAX_TRACK_HEIGHT)
            }
            st.copy(trackHeights = updated)
        }
    }

    fun toggleAutoEase() = _uiState.update { it.copy(autoEase = !it.autoEase) }

    /**
     * Crop tool: scale/move the selected clip directly on the preview. [zoom] is a pinch
     * factor; [panXFrac]/[panYFrac] are drag deltas as a fraction of the preview size.
     */
    fun transformSelectedClip(zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float = 0f) {
        val id = _uiState.value.selectedClipId ?: return
        updateClip(id) {
            it.copy(
                scale = (it.scale * zoom).coerceIn(0.1f, 6f),
                offsetX = (it.offsetX + panXFrac).coerceIn(-1.5f, 1.5f),
                offsetY = (it.offsetY + panYFrac).coerceIn(-1.5f, 1.5f),
                rotation = it.rotation + rotationDelta,
            )
        }
    }
    fun setTool(tool: EditorTool) = _uiState.update { it.copy(tool = tool) }

    fun selectClip(id: String?, additive: Boolean = false) {
        _uiState.update { st ->
            val next = when {
                id == null -> emptyList()
                additive && id in st.selectedClipIds -> st.selectedClipIds - id
                additive -> st.selectedClipIds + id
                else -> listOf(id)
            }
            // Deselecting (additive removal) shouldn't pull the group back in.
            val resolved = if (additive && id != null && id !in next) next else expandGroups(st.document, next)
            st.copy(selectedClipIds = resolved)
        }
    }

    /**
     * Range-select from the current selection (the anchor) to [targetClipId]: selects
     * both clips plus every clip that overlaps the covered time span AND sits on a
     * track between them. Track order follows the timeline layout (video tracks above
     * audio tracks), so the range can span from one track to another — including every
     * track in between. With nothing selected yet, this just selects the target.
     */
    fun selectRangeTo(targetClipId: String) {
        val doc = document
        val target = doc.clips.firstOrNull { it.id == targetClipId } ?: return
        val anchors = _uiState.value.selectedClips
        if (anchors.isEmpty()) {
            selectClip(targetClipId)
            return
        }
        val involved = anchors + target
        val timeStart = involved.minOf { it.startTimeMs }
        val timeEnd = involved.maxOf { it.endTimeMs }

        val orderedTracks = doc.videoTracks + doc.audioTracks
        val indices = involved.mapNotNull { c -> orderedTracks.indexOf(c.trackId).takeIf { i -> i >= 0 } }
        if (indices.isEmpty()) {
            selectClip(targetClipId)
            return
        }
        val tracksInRange = orderedTracks.subList(indices.min(), indices.max() + 1).toSet()

        val ids = doc.clips
            .filter { c -> c.trackId in tracksInRange && c.startTimeMs < timeEnd && c.endTimeMs > timeStart }
            .map { it.id }
        _uiState.update { it.copy(selectedClipIds = expandGroups(doc, ids)) }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedClipIds = emptyList(), selectedKeyframeId = null) }

    /** Replace the whole document (used by project load); resets history. */
    fun loadDocument(doc: Document) {
        past.clear(); future.clear()
        _uiState.update {
            it.copy(document = doc, currentTimeMs = 0, isPlaying = false, selectedClipIds = emptyList(), canUndo = false, canRedo = false)
        }
    }
}
