package com.hereliesaz.guillotine.editor

import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.EditAction
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

enum class EditorTool { SELECT, SPLIT, KEYFRAME, CROP, MARQUEE }

/**
 * Preview render quality: caps the resolution the preview players decode/scale to. Lower levels trade
 * clarity for smoother playback (lighter decode/composite); [FULL] leaves the source untouched.
 * [maxDimension] is the longest-edge pixel cap fed to the player's track-selection parameters.
 */
enum class PreviewQuality(val label: String, val maxDimension: Int) {
    P240("240p", 426),
    P480("480p", 854),
    P720("720p", 1280),
    P1080("1080p", 1920),
    FULL("Full", Int.MAX_VALUE),
}

/** Default on-timeline duration for still images. */
private const val IMAGE_DEFAULT_DURATION_MS = 5_000L
private const val MIN_CLIP_DURATION_MS = 100L
private const val HISTORY_LIMIT = 100
/** A ruler drag shorter than this (ms) clears the playback region instead of defining a tiny one. */
private const val MIN_REGION_MS = 50L

/** Default vertical placement for auto-caption clips: lower third (fraction of frame below center). */
private const val CAPTION_OFFSET_Y = 0.30f
/** Media-name prefix marking a generated face-blur patch (so its overlay clip/track can be undone). */
const val FACE_BLUR_PREFIX = "faceblur:"

data class EditorUiState(
    val document: Document = Document(),
    val currentTimeMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** Loop playback: at the end of the playback region (or the whole timeline) restart from the
     *  beginning instead of stopping. Toggled by the loop button next to Play. */
    val loopPlayback: Boolean = false,
    /** Optional playback / loop region in timeline ms; null = the whole timeline. Defined by dragging
     *  the ruler strip, or by marquee-selecting a range of clips. Playback stops (or loops) at its end. */
    val playbackRegion: LongRange? = null,
    /** Timeline zoom in pixels-per-second (horizontal). */
    val pixelsPerSecond: Float = 100f,
    /** Per-track lane heights in dp (vertical zoom); absent = default. */
    val trackHeights: Map<String, Float> = emptyMap(),
    /** New keyframes get a smooth ease by default; off = linear. */
    val autoEase: Boolean = true,
    val playbackRate: Float = 1f,
    /** Preview render quality (lower = smoother playback, less clarity). */
    val previewQuality: PreviewQuality = PreviewQuality.P720,
    val selectedClipIds: List<String> = emptyList(),
    /** Currently-selected keyframe (shows ease handles on its segment). */
    val selectedKeyframeId: String? = null,
    /** Recent prompts (most-recent first): inline hint, history dropdown, empty-submit default. */
    val promptHistory: List<String> = emptyList(),
    val tool: EditorTool = EditorTool.SELECT,
    val isProcessing: Boolean = false,
    val analysisProgress: com.hereliesaz.guillotine.ai.AnalysisProgress? = null,
    val error: String? = null,
    /** Human-readable name of the current export phase, e.g. "Building Transformer". Cleared when export ends. */
    val exportPhase: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedClipId: String? get() = selectedClipIds.singleOrNull()
    val selectedClips: List<TimelineClip>
        get() = document.clips.filter { it.id in selectedClipIds }

    /** Most recent prompt (the inline hint / empty-submit default). */
    val lastPrompt: String get() = promptHistory.firstOrNull().orEmpty()

    /** Lane height (dp) for [trackId], falling back to the default. */
    fun trackHeight(trackId: String): Float = trackHeights[trackId] ?: DEFAULT_TRACK_HEIGHT
}

const val DEFAULT_TRACK_HEIGHT = 64f
const val MIN_TRACK_HEIGHT = 44f
const val MAX_TRACK_HEIGHT = 240f
private const val MAX_PROMPT_HISTORY = 7

/** Animated caption scale range: syllables start at BASE and grow to PEAK when spoken. */
private const val BASE_SCALE = 0.6f
private const val PEAK_SCALE = 1.2f
/** How far apart syllables spread horizontally (fraction of frame width). */
private const val SPREAD_X = 0.25f

/**
 * Owns all editor state. Content mutations go through [mutateDocument] so undo/redo
 * stays consistent; transient view state (playhead, zoom, selection) is updated
 * directly and is not undoable.
 */
open class EditorViewModel {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val past = ArrayDeque<Document>()
    private val future = ArrayDeque<Document>()

    // Serializes the read-then-write of the undo/redo stacks. MCP tool calls mutate the document on
    // background threads (Dispatchers.IO / relay + NanoHTTPD workers) concurrently with UI edits; without
    // this, two mutations reading the same base document would each push history and one edit would be
    // lost. `MutableStateFlow.update` is already atomic; this makes the whole read-modify-write atomic.
    private val historyLock = Any()

    private val document: Document get() = _uiState.value.document

    val actionRecorder = ActionRecorder()

    private fun describeFilterDiff(before: com.hereliesaz.guillotine.model.ClipFilters, after: com.hereliesaz.guillotine.model.ClipFilters): Map<String, Any> = buildMap {
        if (before.brightness != after.brightness) put("brightness", after.brightness)
        if (before.contrast != after.contrast) put("contrast", after.contrast)
        if (before.saturation != after.saturation) put("saturation", after.saturation)
        if (before.sepia != after.sepia) put("sepia", after.sepia)
        if (before.blur != after.blur) put("blur", after.blur)
        if (before.volume != after.volume) put("volume", after.volume)
        if (before.pan != after.pan) put("pan", after.pan)
        if (before.normalize != after.normalize) put("normalize", after.normalize)
        if (before.hueRotate != after.hueRotate) put("hue_rotate", after.hueRotate)
        if (before.invert != after.invert) put("invert", after.invert)
        if (before.grayscale != after.grayscale) put("grayscale", after.grayscale)
        if (before.removeBackground != after.removeBackground) put("remove_background", after.removeBackground)
    }

    // ---- undo/redo plumbing ------------------------------------------------

    private fun mutateDocument(transform: (Document) -> Document) = synchronized(historyLock) {
        val current = document
        val next = transform(current)
        if (next === current) return@synchronized
        past.addLast(current)
        if (past.size > HISTORY_LIMIT) past.removeFirst()
        future.clear()
        // Zoom is view state, not content — preserve the live UI zoom on the new document so undo/redo
        // never restores an old zoom the user has since changed.
        _uiState.update {
            it.copy(
                document = next.copy(pixelsPerSecond = it.pixelsPerSecond),
                canUndo = past.isNotEmpty(),
                canRedo = false,
            )
        }
    }

    fun undo() = synchronized(historyLock) {
        if (past.isEmpty()) return@synchronized
        val prev = past.removeLast()
        future.addLast(document)
        _uiState.update {
            // Restore content, but keep the current UI zoom (undo shouldn't change how you're looking
            // at the timeline, only what's on it).
            it.copy(
                document = prev.copy(pixelsPerSecond = it.pixelsPerSecond),
                selectedClipIds = it.selectedClipIds.filter { id -> prev.clips.any { c -> c.id == id } },
                canUndo = past.isNotEmpty(),
                canRedo = true,
            )
        }
    }

    fun redo() = synchronized(historyLock) {
        if (future.isEmpty()) return@synchronized
        val next = future.removeLast()
        past.addLast(document)
        _uiState.update {
            it.copy(
                document = next.copy(pixelsPerSecond = it.pixelsPerSecond),
                canUndo = true,
                canRedo = future.isNotEmpty(),
            )
        }
    }

    // ---- media import ------------------------------------------------------

    /**
     * Add imported media as matching clip(s). A single clip lands at the playhead (where the
     * cursor is); when several are added at once they're laid end-to-end from the end of the
     * timeline (batch import keeps its own order rather than stacking on the cursor). If
     * [targetTrack] is a track of the matching type, the clips land there (e.g. importing
     * from a specific track header); otherwise they go to the default V1/A1 track.
     */
    fun addMedia(items: List<MediaItem>, targetTrack: String? = null) {
        if (items.isEmpty()) return
        mutateDocument { doc ->
            val videoTrack = targetTrack?.takeIf { it in doc.videoTracks } ?: "V1"
            val audioTrack = targetTrack?.takeIf { it in doc.audioTracks } ?: "A1"
            val newClips = mutableListOf<TimelineClip>()
            // One clip → at the cursor; multiple → appended sequentially at the end.
            var cursor = if (items.size == 1) _uiState.value.currentTimeMs else doc.totalDurationMs
            for (m in items) {
                when (m.kind) {
                    MediaKind.VIDEO -> {
                        if (m.hasAudio) {
                            // Show the video's audio on the audio track as a linked shadow clip,
                            // grouped with the picture so they move/trim/delete together. The
                            // video clip still plays/exports its own audio; the shadow is a
                            // waveform view (skipped in preview/export to avoid doubling).
                            val gid = newId()
                            val video = videoClip(m, cursor, videoTrack).copy(groupId = gid)
                            newClips += video
                            newClips += audioClip(m, cursor, audioTrack)
                                .copy(groupId = gid, linkedClipId = video.id)
                        } else {
                            newClips += videoClip(m, cursor, videoTrack)
                        }
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
        // Reset the horizontal zoom so every clip is visible after an import — the natural default
        // when the timeline's content just changed. Runs after mutateDocument so totalDurationMs
        // reflects the new clips.
        fitZoomToTimeline()
    }

    /**
     * Background replace (matting): turn on subject segmentation for [foregroundClipId] and drop
     * [background] on a NEW track *behind* it, spanning the foreground clip's timeline range, so the
     * subject is composited over the new background. One undo step. No-op if the clip is gone.
     */
    fun replaceBackground(foregroundClipId: String, background: MediaItem) {
        mutateDocument { doc ->
            val fg = doc.clips.firstOrNull { it.id == foregroundClipId } ?: return@mutateDocument doc
            // Appending to videoTracks puts the new track at the BOTTOM of the panel — rendered first,
            // i.e. behind the (now matted) foreground clip.
            val bgTrack = nextTrackId(doc.videoTracks, "V")
            val bgClip = TimelineClip(
                id = newId(),
                mediaId = background.id,
                type = ClipType.VIDEO,
                trackId = bgTrack,
                startTimeMs = fg.startTimeMs,
                trimStartMs = 0,
                durationMs = fg.durationMs,
            )
            doc.copy(
                videoTracks = doc.videoTracks + bgTrack,
                mediaItems = doc.mediaItems + background,
                clips = doc.clips.map {
                    if (it.id == foregroundClipId) it.copy(filters = it.filters.copy(removeBackground = true)) else it
                } + bgClip,
            )
        }
    }

    /**
     * Face-blur by tracking: drop [patch] (a pre-blurred face patch image) on a NEW track ON TOP of
     * [sourceClipId], spanning its range, with OFFSET_X / OFFSET_Y / SCALE keyframes that follow the
     * face. Built on the keyframe system, so it renders in both preview and export and stays fully
     * editable. The overlay is group-linked to the source so they move/delete together, and its media
     * name is prefixed [FACE_BLUR_PREFIX] so [removeFaceBlurOverlay] can find and undo it. One undo
     * step. Returns the overlay clip id, or null when the source clip is gone.
     */
    fun addFaceBlurOverlay(
        sourceClipId: String,
        patch: MediaItem,
        offsetX: List<Pair<Long, Float>>,
        offsetY: List<Pair<Long, Float>>,
        scale: List<Pair<Long, Float>>,
    ): String? {
        var overlayId: String? = null
        mutateDocument { doc ->
            val src = doc.clips.firstOrNull { it.id == sourceClipId } ?: return@mutateDocument doc
            val track = nextTrackId(doc.videoTracks, "V")
            val ease = CubicBezier()
            fun kfs(list: List<Pair<Long, Float>>, prop: KeyframeProperty) =
                list.map { (t, v) -> Keyframe(newId(), t.coerceIn(0, src.durationMs), v, prop, ease) }
            val keyframes = (kfs(offsetX, KeyframeProperty.OFFSET_X) +
                kfs(offsetY, KeyframeProperty.OFFSET_Y) +
                kfs(scale, KeyframeProperty.SCALE)).sortedBy { it.timeMs }
            val group = src.groupId ?: newId()
            val overlay = TimelineClip(
                id = newId(),
                mediaId = patch.id,
                type = ClipType.VIDEO,
                trackId = track,
                startTimeMs = src.startTimeMs,
                trimStartMs = 0,
                durationMs = src.durationMs,
                keyframes = keyframes,
                groupId = group,
            )
            overlayId = overlay.id
            doc.copy(
                // Prepend → rendered LAST by the reversed track walk → composited ON TOP of the face.
                videoTracks = listOf(track) + doc.videoTracks,
                mediaItems = doc.mediaItems + patch,
                clips = doc.clips.map {
                    if (it.id == sourceClipId) it.copy(groupId = group, filters = it.filters.copy(blurFaces = true)) else it
                } + overlay,
            )
        }
        return overlayId
    }

    /** Remove any face-blur overlay(s) previously added for [sourceClipId], and clear its flag. */
    fun removeFaceBlurOverlay(sourceClipId: String) {
        mutateDocument { doc ->
            val src = doc.clips.firstOrNull { it.id == sourceClipId } ?: return@mutateDocument doc
            val blurMediaIds = doc.mediaItems.filter { it.name.startsWith(FACE_BLUR_PREFIX) }.map { it.id }.toSet()
            // Overlay clips: this source's blur patches (group-linked, blur-patch media).
            val overlayClips = doc.clips.filter {
                it.mediaId in blurMediaIds && it.groupId != null && it.groupId == src.groupId
            }
            val overlayTracks = overlayClips.map { it.trackId }.toSet()
            doc.copy(
                videoTracks = doc.videoTracks.filterNot { it in overlayTracks },
                clips = (doc.clips - overlayClips.toSet()).map {
                    if (it.id == sourceClipId) it.copy(filters = it.filters.copy(blurFaces = false)) else it
                },
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

    /** Filter edit targeting one specific clip (used by the per-clip tool popups on a group). */
    fun updateClipFilters(clipId: String, transform: (ClipFilters) -> ClipFilters) {
        val before = document.clips.firstOrNull { it.id == clipId }?.filters
        updateClip(clipId) { it.copy(filters = transform(it.filters)) }
        if (actionRecorder.isRecording && before != null) {
            val after = document.clips.firstOrNull { it.id == clipId }?.filters ?: return
            val changes = describeFilterDiff(before, after)
            if (changes.isNotEmpty()) {
                actionRecorder.record(RecordedAction("update_filters", mapOf("clip_id" to clipId) + changes, "Adjust filters: ${changes.entries.joinToString { "${it.key}=${it.value}" }}"))
            }
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
        ids.forEach { id -> actionRecorder.record(RecordedAction("set_prompt", mapOf("clip_id" to id, "prompt" to prompt), "Set prompt: \"$prompt\"")) }
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
     * Scissors / blade — split at the playhead in one undo step:
     *  - With a selection, split the selected clip(s). If a selected clip is **grouped**, split every
     *    member of its group and divide the group in two — the left pieces keep the group, the right
     *    pieces become a new group.
     *  - With **no** selection, split every clip on every track that the playhead passes through.
     * A clip's linked audio shadow (a group member) splits with it and its right half re-links to the
     * matching video right half so the two stay paired.
     */
    fun splitAtPlayhead() {
        val now = _uiState.value.currentTimeMs
        val selIds = _uiState.value.selectedClipIds
        mutateDocument { doc ->
            // Targets: all clips (no selection) or the selection expanded to whole groups.
            val targets = if (selIds.isEmpty()) {
                doc.clips
            } else {
                val selGroups = doc.clips.filter { it.id in selIds }.mapNotNull { it.groupId }.toSet()
                doc.clips.filter { it.id in selIds || (it.groupId != null && it.groupId in selGroups) }
            }
            val targetIds = targets.mapTo(HashSet()) { it.id }

            // A group whose target members span across the playhead is divided: its right side becomes
            // a new group (→ two groups). A group fully on one side keeps its id.
            val rightGroupId = HashMap<String, String>()
            targets.filter { it.groupId != null }.groupBy { it.groupId!! }.forEach { (g, members) ->
                val divided = members.minOf { it.startTimeMs } < now && members.maxOf { it.endTimeMs } > now
                rightGroupId[g] = if (divided) newId() else g
            }
            // Pre-assign each split clip's right-half id so a split audio shadow can re-link to its video half.
            fun splitsHere(c: TimelineClip) = (now - c.startTimeMs) in 1 until c.durationMs
            val rightHalfId = targets.filter { splitsHere(it) }.associate { it.id to newId() }

            val anyDivided = rightGroupId.any { (g, r) -> r != g }
            if (rightHalfId.isEmpty() && !anyDivided) return@mutateDocument doc

            doc.copy(clips = doc.clips.flatMap { clip ->
                if (clip.id !in targetIds) return@flatMap listOf(clip)
                val rightG = clip.groupId?.let { rightGroupId[it] }
                val clipTime = now - clip.startTimeMs
                when {
                    clipTime in 1 until clip.durationMs -> {
                        val first = clip.copy(
                            durationMs = clipTime,
                            keyframes = clip.keyframes.filter { it.timeMs <= clipTime },
                        )
                        val second = clip.copy(
                            id = rightHalfId.getValue(clip.id),
                            startTimeMs = clip.startTimeMs + clipTime,
                            trimStartMs = clip.trimStartMs + clipTime,
                            durationMs = clip.durationMs - clipTime,
                            groupId = rightG,
                            linkedClipId = clip.linkedClipId?.let { rightHalfId[it] ?: it },
                            keyframes = clip.keyframes.filter { it.timeMs > clipTime }
                                .map { it.copy(id = newId(), timeMs = it.timeMs - clipTime) },
                        )
                        listOf(first, second)
                    }
                    // A grouped member entirely right of the playhead joins the new right group.
                    clip.groupId != null && clip.startTimeMs >= now -> listOf(clip.copy(groupId = rightG))
                    else -> listOf(clip)
                }
            })
        }
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
        actionRecorder.record(RecordedAction("split_clip", mapOf("clip_id" to clipId, "at_ms" to timelineMs), "Split clip at ${timelineMs}ms"))
    }

    /**
     * Split [clipId] into contiguous, independent pieces at every timeline position in
     * [timelineMsList] — all footage is kept (nothing is deleted or rippled). Used by beat-driven
     * editing to place many cuts on beat times in **one** undo step (unlike calling [splitClip] N
     * times). Positions falling outside the clip, duplicates, and slices below the minimum clip
     * length are dropped; keyframes are partitioned and re-based per piece; a linked shadow-audio
     * clip is cut in lockstep so sound stays in sync.
     */
    fun splitClipAt(clipId: String, timelineMsList: List<Long>) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
        // Accept cut points greedily so every resulting slice (including the head and tail) is at
        // least MIN_CLIP_DURATION_MS. Pre-filtering here — rather than dropping too-short slices
        // after splitting — means no footage is ever discarded: cuts that would strand a tiny slice
        // are simply merged into their neighbour, keeping the timeline gap-free.
        val candidates = timelineMsList
            .map { it - clip.startTimeMs }
            .filter { it > 0 && it < clip.durationMs }
            .distinct()
            .sorted()
        val cuts = ArrayList<Long>()
        cuts.add(0L)
        candidates.forEach { c ->
            if (c - cuts.last() >= MIN_CLIP_DURATION_MS && clip.durationMs - c >= MIN_CLIP_DURATION_MS) {
                cuts.add(c)
            }
        }
        cuts.add(clip.durationMs)
        if (cuts.size <= 2) return // no usable internal cut

        mutateDocument { doc ->
            val shadow = doc.clips.firstOrNull { it.linkedClipId == clip.id }
            val videoPieces = mutableListOf<TimelineClip>()
            val shadowPieces = mutableListOf<TimelineClip>()
            for (i in 0 until cuts.size - 1) {
                val relStart = cuts[i]
                val relEnd = cuts[i + 1]
                val dur = relEnd - relStart
                // Slices are guaranteed >= MIN_CLIP_DURATION_MS by the greedy pre-filter above,
                // so none is dropped here — all footage is preserved.
                val pairGroup = if (shadow != null) newId() else null
                val vid = clip.copy(
                    id = newId(),
                    startTimeMs = clip.startTimeMs + relStart,
                    trimStartMs = clip.trimStartMs + relStart,
                    durationMs = dur,
                    edits = emptyList(),
                    groupId = pairGroup,
                    linkedClipId = null,
                    keyframes = clip.keyframes
                        .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                        .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                )
                videoPieces += vid
                if (shadow != null && pairGroup != null) {
                    shadowPieces += shadow.copy(
                        id = newId(),
                        startTimeMs = shadow.startTimeMs + relStart,
                        trimStartMs = shadow.trimStartMs + relStart,
                        durationMs = dur,
                        edits = emptyList(),
                        groupId = pairGroup,
                        linkedClipId = vid.id,
                        keyframes = shadow.keyframes
                            .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                            .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                    )
                }
            }
            if (videoPieces.isEmpty()) return@mutateDocument doc
            val removed = setOfNotNull(clip.id, shadow?.id)
            doc.copy(clips = doc.clips.filterNot { it.id in removed } + videoPieces + shadowPieces)
        }
        actionRecorder.record(RecordedAction("split_clip_at", mapOf("clip_id" to clipId, "count" to timelineMsList.size), "Split clip at ${timelineMsList.size} points"))
    }

    /**
     * Segmentation: split the selected clip into discrete clips at every AI/edit segment
     * boundary, keeping all pieces in place. Each piece becomes an independent clip (its
     * keyframes partitioned/re-based, edit marks cleared) so the user can rearrange or
     * delete the unwanted ones — complementing non-destructive clip-cutting (keep/remove
     * ranges applied at export).
     */
    fun segmentSelectedClip() {
        val clip = _uiState.value.selectedClips.firstOrNull { it.edits.isNotEmpty() } ?: return
        segmentClip(clip.id)
    }

    /**
     * Segment a specific clip (used by the per-clip tool popups, incl. on a grouped selection). Each
     * video piece is paired with the matching slice of its linked shadow audio in a fresh 2-member
     * group — so dragging one piece moves its own audio with it, but sibling pieces stay independent
     * (segment's whole point is that you can rearrange/delete them one by one, unlike [applyCuts]
     * which groups all pieces together).
     */
    fun segmentClip(clipId: String) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
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
            val shadow = doc.clips.firstOrNull { it.linkedClipId == clip.id }
            val videoPieces = mutableListOf<TimelineClip>()
            val shadowPieces = mutableListOf<TimelineClip>()
            for (i in 0 until cuts.size - 1) {
                val relStart = cuts[i]
                val relEnd = cuts[i + 1]
                val dur = relEnd - relStart
                if (dur < MIN_CLIP_DURATION_MS) continue
                // A fresh 2-member group per (video, shadow-audio) pair so they stay linked while
                // sibling pieces remain independent.
                val pairGroup = if (shadow != null) newId() else null
                val vid = clip.copy(
                    id = newId(),
                    startTimeMs = clip.startTimeMs + relStart,
                    trimStartMs = clip.trimStartMs + relStart,
                    durationMs = dur,
                    edits = emptyList(),
                    groupId = pairGroup,
                    linkedClipId = null,
                    keyframes = clip.keyframes
                        .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                        .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                )
                videoPieces += vid
                if (shadow != null && pairGroup != null) {
                    shadowPieces += shadow.copy(
                        id = newId(),
                        startTimeMs = shadow.startTimeMs + relStart,
                        trimStartMs = shadow.trimStartMs + relStart,
                        durationMs = dur,
                        edits = emptyList(),
                        groupId = pairGroup,
                        // Re-link this slice to its video piece so preview/export still treat it as
                        // a skipped shadow (the video clip carries the sound).
                        linkedClipId = vid.id,
                        keyframes = shadow.keyframes
                            .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                            .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                    )
                }
            }
            if (videoPieces.isEmpty()) return@mutateDocument doc
            val removed = setOfNotNull(clip.id, shadow?.id)
            doc.copy(clips = doc.clips.filterNot { it.id in removed } + videoPieces + shadowPieces)
        }
        _uiState.update { it.copy(selectedClipIds = emptyList()) }
        actionRecorder.record(RecordedAction("segment_clip", mapOf("clip_id" to clipId), "Segment clip at edit boundaries"))
    }

    /** Apply a clip's own keep/remove edits as a real cut. */
    fun applyCuts(clipId: String) = applyCuts(clipId, null)

    /**
     * Turn keep/remove ranges into REAL timeline structure — the same split + delete the user does by
     * hand with the scissors and trash: the kept (non-removed) ranges become **separate clips, grouped
     * together**, butted contiguously (no black gaps); the removed ranges are deleted; the rest of the
     * timeline ripples left; and the linked shadow-audio clip is cut the same way. If every range is
     * removed, the whole clip (and its shadow) is deleted. One undo step.
     *
     * [overrideEdits] lets the analyzer pass its ranges directly so the whole thing happens in ONE atomic
     * mutation and **no REMOVE marks are ever persisted** on the clip — there is no intermediate state
     * where the timeline shows red "scripting" marks. Manual callers pass null to use the clip's own edits.
     */
    fun applyCuts(clipId: String, overrideEdits: List<com.hereliesaz.guillotine.model.EditSegment>?) {
        mutateDocument { doc ->
            val c0 = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            // Compute against the supplied ranges (analyzer) or the clip's existing marks (manual), but
            // never write those marks back — the pieces below carry no edits, so nothing renders red.
            val c = if (overrideEdits != null) c0.copy(edits = overrideEdits) else c0
            if (c.edits.none { it.action == EditAction.REMOVE }) return@mutateDocument doc
            val shadow = doc.clips.firstOrNull { it.linkedClipId == c.id }
            // Kept ranges in source ms -> clip-relative (start, end) intervals shared by clip + shadow.
            val rel = com.hereliesaz.guillotine.model.TimelineMath.keptRanges(c)
                .map { (it.first - c.trimStartMs) to (it.last + 1 - c.trimStartMs) }
            val gid = newId()
            val videoPieces = cutPieces(c, rel, gid)
            // videoPieces empty => every range removed => the clip is deleted outright (removedTotal is
            // its full duration), and later clips ripple left to close the gap.
            val removedTotal = c.durationMs - videoPieces.sumOf { it.durationMs }
            // Re-link each shadow-audio piece to its video piece so preview/export keep treating it as a
            // skipped shadow (otherwise the audio would play twice).
            val shadowPieces = shadow?.let { sh ->
                cutPieces(sh, rel, gid).mapIndexed { i, s ->
                    videoPieces.getOrNull(i)?.let { s.copy(linkedClipId = it.id) } ?: s
                }
            } ?: emptyList()
            val replaced = setOfNotNull(c.id, shadow?.id)
            val origEnd = c.endTimeMs
            val rest = doc.clips.flatMap { cl ->
                when {
                    cl.id in replaced -> emptyList()
                    // Ripple later clips left to close the gap the removed ranges left behind.
                    cl.startTimeMs >= origEnd -> listOf(cl.copy(startTimeMs = cl.startTimeMs - removedTotal))
                    else -> listOf(cl)
                }
            }
            doc.copy(clips = rest + videoPieces + shadowPieces)
        }
        _uiState.update { it.copy(selectedClipIds = emptyList()) }
        actionRecorder.record(RecordedAction("apply_cuts", mapOf("clip_id" to clipId), "Apply keep/remove cuts"))
    }

    /** A generated replacement for a clip-relative range: [relStartMs, relEndMs) -> [media] (e.g. inpaint). */
    data class Replacement(val relStartMs: Long, val relEndMs: Long, val media: MediaItem)

    /**
     * Replace clip-relative ranges of [clipId] with generated media (e.g. inpainted frames) while keeping
     * the clip the SAME length: the clip is split at the range boundaries, replaced ranges become clips
     * referencing the new media and the rest stay original pieces, all grouped and at their original
     * positions/durations. Used by the AI for "remove X but keep it natural / keep the length". One undo
     * step. (Audio is untouched here — see remove_object_generative notes.)
     */
    fun replaceSegmentsWithGenerated(clipId: String, replacements: List<Replacement>) {
        if (replacements.isEmpty()) return
        mutateDocument { doc ->
            val c = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val bounds = sortedSetOf(0L, c.durationMs)
            replacements.forEach {
                bounds.add(it.relStartMs.coerceIn(0, c.durationMs))
                bounds.add(it.relEndMs.coerceIn(0, c.durationMs))
            }
            val cuts = bounds.toList()
            val gid = newId()
            val pieces = mutableListOf<TimelineClip>()
            for (i in 0 until cuts.size - 1) {
                val relStart = cuts[i]
                val relEnd = cuts[i + 1]
                val dur = relEnd - relStart
                if (dur < MIN_CLIP_DURATION_MS) continue
                val repl = replacements.firstOrNull { relStart >= it.relStartMs && relEnd <= it.relEndMs }
                if (repl != null) {
                    // Generated replacement: an image clip of the same span (object removed).
                    pieces += c.copy(
                        id = newId(),
                        mediaId = repl.media.id,
                        startTimeMs = c.startTimeMs + relStart,
                        trimStartMs = 0,
                        durationMs = dur,
                        edits = emptyList(),
                        keyframes = emptyList(),
                        groupId = gid,
                        linkedClipId = null,
                        prompt = "",
                    )
                } else {
                    pieces += piece(c, relStart, dur, c.startTimeMs + relStart, gid)
                }
            }
            if (pieces.isEmpty()) return@mutateDocument doc
            // Split the linked shadow audio at the same boundaries so its audio survives and stays
            // grouped. Kept video segments keep their shadow (linked → skipped on export, video carries
            // audio); generated-image segments unlink the shadow so the ORIGINAL audio still plays there.
            val shadow = doc.clips.firstOrNull { it.linkedClipId == c.id }
            val shadowPieces = shadow?.let { sh ->
                buildList {
                    for (i in 0 until cuts.size - 1) {
                        val relStart = cuts[i]
                        val relEnd = cuts[i + 1]
                        val dur = relEnd - relStart
                        if (dur < MIN_CLIP_DURATION_MS) continue
                        val isReplaced = replacements.any { relStart >= it.relStartMs && relEnd <= it.relEndMs }
                        val videoPiece = pieces.firstOrNull { it.startTimeMs == c.startTimeMs + relStart }
                        add(sh.copy(
                            id = newId(),
                            startTimeMs = c.startTimeMs + relStart,
                            trimStartMs = sh.trimStartMs + relStart,
                            durationMs = dur,
                            edits = emptyList(),
                            groupId = gid,
                            linkedClipId = if (isReplaced) null else videoPiece?.id,
                            keyframes = sh.keyframes
                                .filter { it.timeMs >= relStart && it.timeMs < relEnd }
                                .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
                        ))
                    }
                }
            } ?: emptyList()
            val newMedia = replacements.map { it.media }.filter { m -> doc.mediaItems.none { it.id == m.id } }
            val replaced = setOfNotNull(c.id, shadow?.id)
            doc.copy(
                mediaItems = doc.mediaItems + newMedia,
                clips = doc.clips.flatMap { if (it.id in replaced) emptyList() else listOf(it) } + pieces + shadowPieces,
            )
        }
        _uiState.update { it.copy(selectedClipIds = emptyList()) }
    }

    /** Build contiguous, grouped sub-clips for the given clip-relative kept intervals. */
    private fun cutPieces(clip: TimelineClip, rel: List<Pair<Long, Long>>, groupId: String): List<TimelineClip> {
        var cursor = clip.startTimeMs
        val out = mutableListOf<TimelineClip>()
        for ((relStart, relEnd) in rel) {
            val dur = relEnd - relStart
            if (dur < MIN_CLIP_DURATION_MS) continue
            out += piece(clip, relStart, dur, cursor, groupId)
            cursor += dur
        }
        return out
    }

    /** A fresh sub-clip of [clip]: trim window [relStart, relStart+dur) placed at [startTimeline]. */
    private fun piece(clip: TimelineClip, relStart: Long, dur: Long, startTimeline: Long, groupId: String? = clip.groupId) =
        clip.copy(
            id = newId(),
            startTimeMs = startTimeline,
            trimStartMs = clip.trimStartMs + relStart,
            durationMs = dur,
            edits = emptyList(),
            groupId = groupId,
            linkedClipId = null,
            keyframes = run {
                // Half-open [relStart, relStart+dur) assigns a keyframe on an interior cut boundary to the
                // NEXT piece (where it sits at relStart). But the piece that reaches the clip's END has no
                // "next" piece, so include its closing boundary there — otherwise a keyframe at the clip's
                // final ms is dropped (matches splitClip, which keeps it).
                val end = relStart + dur
                val includeEnd = end >= clip.durationMs
                clip.keyframes
                    .filter { it.timeMs >= relStart && (if (includeEnd) it.timeMs <= end else it.timeMs < end) }
                    .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) }
            },
        )

    /**
     * Generic ripple delete: cut the timeline span [startMs, endMs) out of every track — clips fully
     * inside vanish, overlapping clips are trimmed, and everything after shifts left to close the gap.
     */
    fun rippleDeleteRange(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        mutateDocument { doc -> doc.copy(clips = doc.clips.flatMap { rippleClip(it, startMs, endMs) }) }
        actionRecorder.record(RecordedAction("ripple_delete_range", mapOf("start_ms" to startMs, "end_ms" to endMs), "Ripple delete ${startMs}ms–${endMs}ms"))
    }

    private fun rippleClip(c: TimelineClip, start: Long, end: Long): List<TimelineClip> {
        val gap = end - start
        return when {
            c.endTimeMs <= start -> listOf(c)                              // entirely before the cut
            c.startTimeMs >= end -> listOf(c.copy(startTimeMs = c.startTimeMs - gap)) // entirely after → ripple
            else -> buildList {                                            // overlaps the cut → keep the edges
                if (c.startTimeMs < start) {
                    val dur = start - c.startTimeMs
                    if (dur >= MIN_CLIP_DURATION_MS) add(piece(c, 0, dur, c.startTimeMs))
                }
                if (c.endTimeMs > end) {
                    val dur = c.endTimeMs - end
                    if (dur >= MIN_CLIP_DURATION_MS) add(piece(c, end - c.startTimeMs, dur, start))
                }
            }
        }
    }

    /**
     * Ripple / close gaps: pull clips left to remove empty space, so the timeline has no dead air.
     * Operates on the selected clips, or **all** clips when nothing is selected. To keep every track
     * in sync, a "gap" is a span empty across ALL clips within the target region (a time covered by a
     * clip on any track is not a gap); closing each such span shifts everything after it left by the
     * same amount. The earliest target clip stays where it is — only the gaps between clips collapse.
     * One undo step.
     */
    fun rippleCloseGaps() {
        val st = _uiState.value
        val targets = if (st.selectedClipIds.isEmpty()) st.document.clips else st.selectedClips
        if (targets.isEmpty()) return
        val regionStart = targets.minOf { it.startTimeMs }
        val regionEnd = targets.maxOf { it.endTimeMs }
        mutateDocument { doc ->
            // Occupied spans (any clip) clipped to the region, sorted — gaps are what's left uncovered.
            val occ = doc.clips
                .mapNotNull {
                    val s = maxOf(it.startTimeMs, regionStart)
                    val e = minOf(it.endTimeMs, regionEnd)
                    if (e > s) s to e else null
                }
                .sortedBy { it.first }
            val gaps = mutableListOf<Pair<Long, Long>>()
            var cursor = regionStart
            for ((s, e) in occ) {
                if (s > cursor) gaps.add(cursor to s)
                cursor = maxOf(cursor, e)
            }
            if (gaps.isEmpty()) return@mutateDocument doc
            // Each clip shifts left by the total length of gaps that lie entirely before its start.
            doc.copy(clips = doc.clips.map { c ->
                val shift = gaps.filter { it.second <= c.startTimeMs }.sumOf { it.second - it.first }
                if (shift > 0) c.copy(startTimeMs = c.startTimeMs - shift) else c
            })
        }
    }

    /**
     * Marquee select: select every clip whose time span overlaps [startMs, endMs) and whose track is
     * included in [trackIds]. Used by the timeline's range-select (marquee) tool.
     */
    fun selectClipsInRect(startMs: Long, endMs: Long, trackIds: Set<String>) {
        val lo = minOf(startMs, endMs)
        val hi = maxOf(startMs, endMs)
        if (hi <= lo || trackIds.isEmpty()) {
            _uiState.update { it.copy(selectedClipIds = emptyList()) }
            return
        }
        val selected = document.clips.filter { it.startTimeMs < hi && it.endTimeMs > lo && it.trackId in trackIds }
        _uiState.update { st ->
            // Selecting a range of clips also defines the playback / loop region over the selected
            // clips' extent, so the user can immediately loop just that region.
            val region = if (selected.isNotEmpty()) {
                selected.minOf { it.startTimeMs }..selected.maxOf { it.endTimeMs }
            } else {
                st.playbackRegion
            }
            st.copy(selectedClipIds = selected.map { it.id }, playbackRegion = region)
        }
    }

    /** Delete a single clip by id, including its linked shadow audio and any group members. */
    fun deleteClip(clipId: String) {
        mutateDocument { doc ->
            val c = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val ids = expandGroups(doc, listOf(clipId)).toMutableSet()
            doc.clips.firstOrNull { it.linkedClipId == clipId }?.let { ids.add(it.id) }
            c.linkedClipId?.let { ids.add(it) }
            doc.copy(clips = doc.clips.filter { it.id !in ids })
        }
        _uiState.update { st -> st.copy(selectedClipIds = st.selectedClipIds.filter { id -> document.clips.any { it.id == id } }) }
        actionRecorder.record(RecordedAction("delete_clip", mapOf("clip_id" to clipId), "Delete clip"))
    }

    private fun trackListFor(doc: Document, type: ClipType): List<String> = when (type) {
        ClipType.VIDEO, ClipType.TEXT -> doc.videoTracks
        ClipType.AUDIO -> doc.audioTracks
    }

    /**
     * Move a clip by a time delta and a track-index shift. If the clip is grouped, every
     * group member moves by the same delta/shift (each clamped to its own track list), so
     * grouped clips drag together.
     */
    private fun dragSetOf(clip: TimelineClip): Set<String> {
        val clipGroupId = clip.groupId
        val group = if (clipGroupId != null) {
            val set = LinkedHashSet<String>()
            for (c in document.clips) {
                if (c.groupId == clipGroupId) set.add(c.id)
            }
            set
        } else {
            setOf(clip.id)
        }
        val sel = uiState.value.selectedClipIds
        if (clip.id !in sel) return group
        val allIds = LinkedHashSet(group)
        allIds.addAll(sel)
        val groupIds = LinkedHashSet<String>()
        for (c in document.clips) {
            if (c.id in allIds) {
                val gId = c.groupId
                if (gId != null) groupIds.add(gId)
            }
        }
        if (groupIds.isNotEmpty()) {
            for (c in document.clips) {
                val gId = c.groupId
                if (gId != null && gId in groupIds) allIds.add(c.id)
            }
        }
        return allIds
    }

    fun moveClipBy(clipId: String, trackShift: Int, deltaMs: Long) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
        val moveIds = dragSetOf(clip)
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { c ->
                if (c.id !in moveIds) return@map c
                val tracks = trackListFor(doc, c.type)
                val curIdx = tracks.indexOf(c.trackId)
                val newIdx = (curIdx + trackShift).coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
                val newTrack = tracks.getOrElse(newIdx) { c.trackId }
                c.copy(trackId = newTrack, startTimeMs = (c.startTimeMs + deltaMs).coerceAtLeast(0))
            })
        }
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
            val newStart = newStartMs.coerceAtLeast(0)
            val delta = newStart - clip.startTimeMs
            // The primary clip changes track + position; its group members and its linked audio shadow
            // (which lives on an audio track and can't follow onto a video track) keep their tracks but
            // shift by the same delta, so grouped clips and video↔audio sync don't break on a move.
            val gid = clip.groupId
            val followIds = doc.clips.asSequence().filter {
                it.id != clipId && (
                    (gid != null && it.groupId == gid) ||
                        it.linkedClipId == clipId ||
                        it.id == clip.linkedClipId
                    )
            }.map { it.id }.toHashSet()
            doc.copy(
                clips = doc.clips.map {
                    when {
                        it.id == clipId -> it.copy(trackId = targetTrackId, startTimeMs = newStart)
                        it.id in followIds -> it.copy(startTimeMs = (it.startTimeMs + delta).coerceAtLeast(0))
                        else -> it
                    }
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
            // The clip's linked waveform shadow (its own audio) trims identically so they stay aligned.
            val affected = setOf(clipId) + doc.clips.filter { it.linkedClipId == clipId }.map { it.id }
            doc.copy(clips = doc.clips.map {
                if (it.id !in affected) it
                else it.copy(
                    startTimeMs = (it.startTimeMs + d).coerceAtLeast(0),
                    trimStartMs = it.trimStartMs + d,
                    durationMs = it.durationMs - d,
                    keyframes = it.keyframes.map { k -> k.copy(timeMs = k.timeMs - d) }.filter { k -> k.timeMs >= 0 },
                )
            })
        }
        actionRecorder.record(RecordedAction("trim_start", mapOf("clip_id" to clipId, "delta_ms" to deltaMs), "Trim start by ${deltaMs}ms"))
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
            // The clip's linked waveform shadow (its own audio) extends/shrinks identically.
            val affected = setOf(clipId) + doc.clips.filter { it.linkedClipId == clipId }.map { it.id }
            doc.copy(clips = doc.clips.map {
                if (it.id !in affected) it
                else {
                    val nd = it.durationMs + d
                    it.copy(durationMs = nd, keyframes = it.keyframes.filter { k -> k.timeMs <= nd })
                }
            })
        }
        actionRecorder.record(RecordedAction("trim_end", mapOf("clip_id" to clipId, "delta_ms" to deltaMs), "Trim end by ${deltaMs}ms"))
    }

    /** Next non-colliding track id for [prefix] ("V"/"A"): one past the max existing numeric suffix
     *  (so it stays unique even after tracks were deleted). */
    private fun nextTrackId(existing: List<String>, prefix: String): String {
        val maxN = existing.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
        return "$prefix${maxN + 1}"
    }

    fun addTrack(type: ClipType) {
        mutateDocument { doc ->
            when (type) {
                // Text lives on video tracks, so "add track" for a text selection adds a video track.
                ClipType.VIDEO, ClipType.TEXT -> doc.copy(videoTracks = doc.videoTracks + nextTrackId(doc.videoTracks, "V"))
                ClipType.AUDIO -> doc.copy(audioTracks = doc.audioTracks + nextTrackId(doc.audioTracks, "A"))
            }
        }
    }

    /**
     * Create a fresh track at the top (prepend) or bottom (append) of the dragged clip's own track list
     * and move the clip onto it. Used when a clip is dragged past the first/last lane of its type and
     * held — a new video/audio track (matching the clip) appears there and the clip lands on it.
     * [deltaMs] carries the in-progress horizontal drag so the clip keeps its time position. One undo step.
     */
    fun addEdgeTrackAndMoveClip(clipId: String, atTop: Boolean, deltaMs: Long) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
        val shiftIds = dragSetOf(clip)
        mutateDocument { doc ->
            val isAudio = clip.type == ClipType.AUDIO
            val tracks = if (isAudio) doc.audioTracks else doc.videoTracks
            val newId = nextTrackId(tracks, if (isAudio) "A" else "V")
            val updatedTracks = if (atTop) listOf(newId) + tracks else tracks + newId
            val movedClips = doc.clips.map { c ->
                when {
                    c.id == clipId -> c.copy(trackId = newId, startTimeMs = (c.startTimeMs + deltaMs).coerceAtLeast(0))
                    c.id in shiftIds -> c.copy(startTimeMs = (c.startTimeMs + deltaMs).coerceAtLeast(0))
                    else -> c
                }
            }
            if (isAudio) doc.copy(audioTracks = updatedTracks, clips = movedClips)
            else doc.copy(videoTracks = updatedTracks, clips = movedClips)
        }
    }

    /** Edit the caption text of a [ClipType.TEXT] clip. */
    fun setClipText(clipId: String, text: String) = updateClip(clipId) { it.copy(text = text) }

    /** Set the typeface of a [ClipType.TEXT] clip. */
    fun setClipFont(clipId: String, font: com.hereliesaz.guillotine.model.TextFont) =
        updateClip(clipId) { it.copy(font = font) }

    // ---- kinetic typography (azphalt motion presets) -----------------------

    /**
     * Apply a kinetic-typography motion preset to a caption. [motionJsonBytes] is the `az-motion` asset
     * payload from the plugin's `.azp`; [pluginId] is the package id (recorded on the clip for the UI).
     * The preset is baked into `generated` keyframes on the clip — after first stripping any previously
     * baked ones, so switching presets is clean and the user's own keyframes on the same channels survive.
     * No-op when the clip is gone or the preset produces no keyframes. One undo step.
     */
    fun applyCaptionMotion(clipId: String, motionJsonBytes: ByteArray, pluginId: String) {
        var applied = false
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val baked = try {
                com.hereliesaz.guillotine.azphalt.AzpMotionInterpreter.bakeFromBytes(motionJsonBytes, clip)
            } catch (_: Exception) {
                return@mutateDocument doc
            }
            if (baked.isEmpty()) return@mutateDocument doc
            val userKfs = clip.keyframes.filterNot { it.generated } // keep hand-authored keyframes
            applied = true
            doc.copy(clips = doc.clips.map {
                if (it.id == clipId) it.copy(keyframes = (userKfs + baked).sortedBy { k -> k.timeMs }, azpPluginId = pluginId)
                else it
            })
        }
        if (applied) actionRecorder.record(RecordedAction("apply_caption_motion", mapOf("clip_id" to clipId, "plugin_id" to pluginId), "Apply kinetic preset $pluginId to $clipId"))
    }

    /** Remove the baked motion-preset keyframes from a caption (leaving user keyframes) and clear its
     *  applied-preset marker. One undo step; no-op if the clip has no generated keyframes. */
    fun clearCaptionMotion(clipId: String) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            if (clip.keyframes.none { it.generated } && clip.azpPluginId == null) return@mutateDocument doc
            doc.copy(clips = doc.clips.map {
                if (it.id == clipId) it.copy(keyframes = it.keyframes.filterNot { k -> k.generated }, azpPluginId = null)
                else it
            })
        }
        actionRecorder.record(RecordedAction("clear_caption_motion", mapOf("clip_id" to clipId), "Clear kinetic preset from $clipId"))
    }

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
     * Add a TEXT clip with [text] on [trackId] at [startMs] for [durationMs], returning its id. Unlike
     * [addEmptyTextClip] (playhead-anchored, no return) this takes explicit content/placement — used by
     * the `add_text` MCP tool so the agent can author title/caption cards.
     */
    fun addTextClip(trackId: String, text: String, startMs: Long, durationMs: Long): String {
        require(trackId in document.videoTracks) { "Track $trackId does not exist or is not a video track." }
        val id = newId()
        mutateDocument { doc ->
            doc.copy(
                clips = doc.clips + TimelineClip(
                    id = id,
                    mediaId = "",
                    type = ClipType.TEXT,
                    trackId = trackId,
                    startTimeMs = startMs.coerceAtLeast(0),
                    trimStartMs = 0,
                    durationMs = durationMs.coerceAtLeast(100),
                    text = text,
                ),
            )
        }
        return id
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
                    // Sit captions in the lower third by default (offsetY is a fraction of the frame
                    // below center; +down in both preview and export) instead of dead-center over the
                    // subject — the standard subtitle position. Users can still move them via crop.
                    // 0.30 keeps them clear of the 9:16 bottom safe zone (bottom 20%).
                    offsetY = CAPTION_OFFSET_Y,
                )
            }
            if (textClips.isEmpty()) return@mutateDocument doc
            val withGroup = docWithTrack.clips.map { if (it.id == sourceClipId) it.copy(groupId = gid) else it }
            docWithTrack.copy(clips = withGroup + textClips)
        }
    }

    /**
     * Animated captions: each word from [wordCues] is split into syllables, and each syllable
     * becomes its own text clip on a separate video track so they all display simultaneously.
     * All syllables of a word share the same start time and duration. Each syllable gets a
     * SCALE keyframe that ramps from [BASE_SCALE] to [PEAK_SCALE] during its portion of the
     * word, giving a "grow as spoken" effect.
     */
    fun addAnimatedCaptionsFromTranscript(
        sourceClipId: String,
        wordCues: List<com.hereliesaz.guillotine.ai.WordCue>,
    ) {
        if (wordCues.isEmpty()) return
        mutateDocument { doc ->
            val source = doc.clips.firstOrNull { it.id == sourceClipId } ?: return@mutateDocument doc
            val gid = source.groupId ?: newId()
            val clipStartSrc = source.trimStartMs
            val clipEndSrc = source.trimStartMs + source.durationMs

            // Split every word into syllables to find the max syllable count (= tracks needed).
            data class SyllableWord(
                val syllables: List<String>,
                val startMs: Long,
                val endMs: Long,
            )

            val words = wordCues.mapNotNull { wc ->
                val s = wc.startMs.coerceIn(clipStartSrc, clipEndSrc)
                val e = wc.endMs.coerceIn(clipStartSrc, clipEndSrc)
                if (e <= s || wc.word.isBlank()) return@mapNotNull null
                SyllableWord(com.hereliesaz.guillotine.ai.SyllableSplitter.split(wc.word), s, e)
            }
            if (words.isEmpty()) return@mutateDocument doc

            val maxSyllables = words.maxOf { it.syllables.size }

            // Ensure we have enough video tracks for the syllables.
            var tracks = doc.videoTracks.toMutableList()
            while (tracks.size < maxSyllables) tracks.add("V${tracks.size + 1}")
            var updatedDoc = doc.copy(videoTracks = tracks)

            val newClips = mutableListOf<TimelineClip>()
            for (word in words) {
                val wordDurMs = word.endMs - word.startMs
                val timelineStart = source.startTimeMs + (word.startMs - clipStartSrc)
                val syllableCount = word.syllables.size

                // Horizontal offset: spread syllables across the frame center.
                // Each syllable gets a fraction of the width based on its character count.
                val totalChars = word.syllables.sumOf { it.length }.coerceAtLeast(1)
                var charCursor = 0

                for ((si, syllable) in word.syllables.withIndex()) {
                    val track = tracks[si]

                    // Horizontal position: center the word, offset each syllable proportionally.
                    val charMid = charCursor + syllable.length / 2f
                    val ox = ((charMid / totalChars) - 0.5f) * SPREAD_X
                    charCursor += syllable.length

                    // Syllable timing within the word: evenly divide the word duration.
                    val syllableStartRel = (si.toLong() * wordDurMs / syllableCount)
                    val syllableEndRel = ((si + 1).toLong() * wordDurMs / syllableCount)

                    val keyframes = listOf(
                        Keyframe(newId(), 0, BASE_SCALE, KeyframeProperty.SCALE),
                        Keyframe(newId(), syllableStartRel, BASE_SCALE, KeyframeProperty.SCALE),
                        Keyframe(newId(), syllableEndRel, PEAK_SCALE, KeyframeProperty.SCALE),
                        Keyframe(newId(), wordDurMs, PEAK_SCALE, KeyframeProperty.SCALE),
                    )

                    newClips += TimelineClip(
                        id = newId(),
                        mediaId = "",
                        type = ClipType.TEXT,
                        trackId = track,
                        startTimeMs = timelineStart,
                        trimStartMs = 0,
                        durationMs = wordDurMs,
                        text = syllable,
                        groupId = gid,
                        scale = BASE_SCALE,
                        offsetX = ox,
                        keyframes = keyframes,
                    )
                }
            }

            val withGroup = updatedDoc.clips.map {
                if (it.id == sourceClipId) it.copy(groupId = gid) else it
            }
            updatedDoc.copy(clips = withGroup + newClips)
        }
    }

    // ---- keyframes ---------------------------------------------------------

    fun addKeyframe(clipId: String, property: KeyframeProperty) {
        val relMs = (_uiState.value.currentTimeMs - (document.clips.firstOrNull { it.id == clipId }?.startTimeMs ?: 0)).coerceAtLeast(0)
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val rel = (_uiState.value.currentTimeMs - clip.startTimeMs).coerceIn(0, clip.durationMs)
                // Capture the property's CURRENT value at the playhead — its interpolated value if already
                // keyframed, else the clip's static value (scale/rotation/offset/volume/…). Hardcoding 1f
                // snapped a keyframed-at look (e.g. brightness 1.5, scale 2.0) back to default at that time.
                val default = com.hereliesaz.guillotine.model.TimelineMath.valueAt(clip, property, rel, property.staticValue(clip))
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
        actionRecorder.record(RecordedAction("add_keyframe", mapOf("clip_id" to clipId, "property" to property.name, "at_rel_ms" to relMs), "Add ${property.name} keyframe at ${relMs}ms"))
    }

    private fun easingNow() =
        if (_uiState.value.autoEase) com.hereliesaz.guillotine.model.CubicBezier()
        else com.hereliesaz.guillotine.model.CubicBezier(0f, 0f, 1f, 1f)

    /**
     * Record one setting's current value as a keyframe at the playhead on [clipId] (used by the per-
     * slider keyframe diamonds in the inspector). No-op if the playhead isn't within the clip.
     */
    fun keyframeSettingAtPlayhead(clipId: String, property: KeyframeProperty) {
        val now = _uiState.value.currentTimeMs
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            if (now < clip.startTimeMs || now > clip.endTimeMs) return@mutateDocument doc
            val rel = (now - clip.startTimeMs).coerceIn(0, clip.durationMs)
            doc.copy(clips = doc.clips.map {
                if (it.id == clipId) it.copy(keyframes = it.keyframes.withKeyframeAt(it, rel, property)) else it
            })
        }
    }

    /**
     * Return this keyframe list with one recorded at [rel] for [property], capturing the property's
     * **current interpolated** value at that time (so a new keyframe doesn't disturb an existing
     * curve), overwriting any keyframe already at the same time/property to avoid duplicates.
     */
    private fun List<Keyframe>.withKeyframeAt(clip: TimelineClip, rel: Long, property: KeyframeProperty): List<Keyframe> {
        val value = com.hereliesaz.guillotine.model.TimelineMath.valueAt(clip, property, rel, property.staticValue(clip))
        val existing = firstOrNull { it.property == property && it.timeMs == rel }
        return if (existing != null) {
            map { if (it.id == existing.id) it.copy(value = value) else it }
        } else {
            (this + Keyframe(newId(), rel, value, property, easingNow())).sortedBy { it.timeMs }
        }
    }

    /**
     * Keyframe button — record the selected clip's crop/placement (+opacity) at the playhead: a
     * keyframe for SCALE/ROTATION/OFFSET_X/OFFSET_Y/OPACITY at their current values, on the selected
     * clip(s) the cursor is over (and only those — never other group members). One undo step.
     */
    fun addKeyframeAtPlayhead() {
        val now = _uiState.value.currentTimeMs
        val ids = _uiState.value.selectedClipIds.toHashSet()
        if (ids.isEmpty()) return
        val props = listOf(
            KeyframeProperty.OPACITY, KeyframeProperty.SCALE, KeyframeProperty.ROTATION,
            KeyframeProperty.OFFSET_X, KeyframeProperty.OFFSET_Y,
        )
        mutateDocument { doc ->
            var changed = false
            val clips = doc.clips.map { clip ->
                if (clip.id !in ids || now < clip.startTimeMs || now > clip.endTimeMs) return@map clip
                val rel = (now - clip.startTimeMs).coerceIn(0, clip.durationMs)
                var kfs = clip.keyframes
                props.forEach { p -> kfs = kfs.withKeyframeAt(clip, rel, p) }
                changed = true
                clip.copy(keyframes = kfs)
            }
            if (changed) doc.copy(clips = clips) else doc
        }
    }

    /**
     * Insert keyframes at arbitrary clip-relative times, values, and easings — the entry point the
     * beat-driven `apply_on_beat` tool needs (the playhead-based [addKeyframe]/[keyframeSettingAtPlayhead]
     * can only capture the current value at the cursor). Each point's time is coerced into the clip;
     * a keyframe already at the same (time, property) is overwritten. Run this **after** any cutting,
     * since split/segment re-base keyframes. One undo step for the whole batch.
     */
    fun insertKeyframes(
        clipId: String,
        property: KeyframeProperty,
        points: List<Triple<Long, Float, CubicBezier>>,
    ) {
        if (points.isEmpty()) return
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                var kfs = clip.keyframes
                points.forEach { (relRaw, valueRaw, easing) ->
                    val rel = relRaw.coerceIn(0, clip.durationMs)
                    // Clamp to the property's valid range so a caller (e.g. apply_on_beat) can't inject an
                    // out-of-range value — a SPEED keyframe of 0 or negative would make TimelineMath's
                    // source-time integration stall or run backwards (a frozen/garbled frame lookup).
                    val value = valueRaw.coerceIn(property.uiRange)
                    val existing = kfs.firstOrNull { it.property == property && it.timeMs == rel }
                    kfs = if (existing != null) {
                        kfs.map { if (it.id == existing.id) it.copy(value = value, easing = easing) else it }
                    } else {
                        kfs + Keyframe(newId(), rel, value, property, easing)
                    }
                }
                clip.copy(keyframes = kfs.sortedBy { it.timeMs })
            })
        }
        actionRecorder.record(RecordedAction("insert_keyframes", mapOf("clip_id" to clipId, "property" to property.name, "count" to points.size), "Insert ${points.size} ${property.name} keyframes"))
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
        _uiState.update {
            it.copy(
                isProcessing = processing,
                error = error,
                // End-of-work should always clear the export phase; a stale "Building Transformer"
                // line hanging after success or failure is confusing.
                exportPhase = if (processing) it.exportPhase else null,
            )
        }
    }

    /** Set (or clear with null) the human-readable current export phase; shown in ExportSheet. */
    fun setExportPhase(phase: String?) {
        _uiState.update { it.copy(exportPhase = phase) }
    }

    fun setAnalysisProgress(progress: com.hereliesaz.guillotine.ai.AnalysisProgress?) {
        _uiState.update { it.copy(analysisProgress = progress) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Set the project name (autosaved with the document; not an undo step). */
    fun setProjectName(name: String) =
        _uiState.update { it.copy(document = it.document.copy(name = name.trim())) }

    /**
     * Record a submitted prompt at the head of the project's prompt history (deduped,
     * capped at [MAX_PROMPT_HISTORY]). Stored on the live UI state AND the document so it
     * persists with the saved project. Updated outside [mutateDocument] (no undo step).
     */
    fun rememberPrompt(prompt: String) {
        val p = prompt.trim()
        if (p.isBlank()) return
        _uiState.update { st ->
            val hist = (listOf(p) + st.promptHistory.filter { it != p }).take(MAX_PROMPT_HISTORY)
            st.copy(promptHistory = hist, document = st.document.copy(promptHistory = hist))
        }
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
                    // A pasted clip is a standalone copy: clear the source's group and linked-shadow
                    // bindings so it doesn't move/delete with the original or get mistaken for its
                    // audio shadow (which would drop it from preview/export).
                    groupId = null,
                    linkedClipId = null,
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
        // On an empty timeline totalDurationMs is 0; still allow scrubbing the (visible) ruler
        // so the cursor can be placed where the next clip should land. Clamp to clips otherwise.
        val total = document.totalDurationMs
        val clamped = if (total > 0) ms.coerceIn(0, total) else ms.coerceAtLeast(0)
        _uiState.update { it.copy(currentTimeMs = clamped) }
    }

    /**
     * Advance the playhead by [deltaMs], honouring the playback region and loop mode. The effective
     * playback window is the [playbackRegion] (clamped to content) if set, else the whole timeline. At
     * the end of that window: loop back to its start when [loopPlayback] is on, otherwise stop there.
     */
    fun advancePlayhead(deltaMs: Long) {
        val st = _uiState.value
        val total = st.document.totalDurationMs
        val region = st.playbackRegion
        val regionStart = (region?.first ?: 0L).coerceIn(0L, total)
        val regionEnd = (region?.last ?: total).coerceIn(0L, total)
        // Only enforce the region while the playhead is still within/before it. If playback started
        // from AFTER the region end, fall back to the whole timeline so content past the region is
        // still playable, rather than instantly stopping (or looping back into the region).
        val insideRegion = st.currentTimeMs < regionEnd
        val startMs = if (insideRegion) regionStart else 0L
        val endMs = if (insideRegion) regionEnd else total
        val next = st.currentTimeMs + deltaMs
        when {
            endMs <= startMs -> _uiState.update { it.copy(isPlaying = false) } // nothing to play
            next >= endMs ->
                if (st.loopPlayback) _uiState.update { it.copy(currentTimeMs = startMs) }
                else _uiState.update { it.copy(currentTimeMs = endMs, isPlaying = false) }
            else -> _uiState.update { it.copy(currentTimeMs = next.coerceAtLeast(0)) }
        }
    }

    /** Where the playhead was last stationary (before playback started) — the return point for [stop]. */
    private var playbackStartMs = 0L

    /**
     * Begin playback. [stop] returns the playhead to where it was last stationary — its position the
     * instant before play was pressed, captured BEFORE any region jump. When a playback region is set,
     * playback then jumps to the region's start; otherwise it starts at the playhead.
     */
    private fun startPlayback() {
        val total = document.totalDurationMs
        if (total <= 0) return
        val current = _uiState.value.currentTimeMs
        playbackStartMs = current // last stationary position — where stop() returns to
        val startAt = _uiState.value.playbackRegion?.first?.coerceIn(0L, total) ?: current
        _uiState.update { it.copy(currentTimeMs = startAt, isPlaying = true) }
    }

    /** Play / pause — pausing leaves the playhead where it is. */
    fun togglePlay() {
        if (_uiState.value.isPlaying) _uiState.update { it.copy(isPlaying = false) }
        else startPlayback()
    }

    fun setPlaying(playing: Boolean) {
        if (playing) startPlayback() else _uiState.update { it.copy(isPlaying = false) }
    }

    /** Stop — halt playback and return the playhead to where it was last stationary (vs. pause, which stays). */
    fun stop() {
        _uiState.update { it.copy(isPlaying = false) }
        seekTo(playbackStartMs)
    }

    /** Transport toggle used by the volume-up+down combo: stop (return to last stationary) if playing, else play. */
    fun playOrStop() {
        if (_uiState.value.isPlaying) stop() else startPlayback()
    }

    /** Loop playback on/off (restart at the window start vs. stop at its end). */
    fun toggleLoop() = _uiState.update { it.copy(loopPlayback = !it.loopPlayback) }

    /** Define the playback / loop region (timeline ms). A span shorter than [MIN_REGION_MS] clears it. */
    fun setPlaybackRegion(startMs: Long, endMs: Long) {
        val lo = minOf(startMs, endMs).coerceAtLeast(0)
        val hi = maxOf(startMs, endMs)
        if (hi - lo < MIN_REGION_MS) { clearPlaybackRegion(); return }
        _uiState.update { it.copy(playbackRegion = lo..hi) }
    }

    fun clearPlaybackRegion() = _uiState.update { it.copy(playbackRegion = null) }
    fun setPlaybackRate(rate: Float) = _uiState.update { it.copy(playbackRate = rate) }
    fun setPreviewQuality(quality: PreviewQuality) = _uiState.update { it.copy(previewQuality = quality) }
    /** Step the preview quality one level (LOW→MEDIUM→HIGH→FULL→LOW), for the transport-bar toggle. */
    fun cyclePreviewQuality() = _uiState.update {
        val vals = PreviewQuality.entries
        it.copy(previewQuality = vals[(it.previewQuality.ordinal + 1) % vals.size])
    }
    /** Visible width (px) of the timeline lanes area; feeds the dynamic zoom-out limit. */
    private var timelineViewportPx = 0f
    fun setTimelineViewportPx(px: Float) {
        val prev = timelineViewportPx
        timelineViewportPx = px
        // First measurement of the viewport for a project with no persisted zoom: snap to fit-all
        // so a freshly-loaded project starts with every clip visible. Subsequent viewport reports
        // (rotation, split-screen resize) don't re-fit — we don't want to clobber a user's zoom.
        if (prev == 0f && px > 0f && document.pixelsPerSecond == null && document.totalDurationMs > 0) {
            fitZoomToTimeline()
        }
    }
    /** Visible height (dp) of the timeline lanes area; feeds the vertical half of fit-all. */
    private var timelineLanesHeightDp = 0f
    fun setTimelineLanesHeightDp(dp: Float) { timelineLanesHeightDp = dp }

    /**
     * Fit every clip on every track onto the visible timeline — horizontally (zoom-to-fit the whole
     * duration in the width) and vertically (give every track an equal share of the lanes height,
     * clamped to [MIN_TRACK_HEIGHT]..[MAX_TRACK_HEIGHT] so tracks stay usable). No-op until both
     * viewport dimensions have been reported by the UI.
     */
    fun fitAllToViewport() {
        fitZoomToTimeline()
        val laneH = timelineLanesHeightDp
        val ids = document.videoTracks + document.audioTracks
        if (laneH <= 0f || ids.isEmpty()) return
        val per = (laneH / ids.size).coerceIn(MIN_TRACK_HEIGHT, MAX_TRACK_HEIGHT)
        _uiState.update { st ->
            val hm = st.trackHeights.toMutableMap()
            ids.forEach { hm[it] = per }
            st.copy(trackHeights = hm)
        }
    }

    fun setZoom(pxPerSec: Float) {
        val clamped = clampPps(pxPerSec)
        // Update BOTH the UI zoom AND the document's persisted zoom in one shot — writing
        // pixelsPerSecond onto document without going through mutateDocument keeps it out of undo
        // history (zoom is view state) while still letting autosave persist it.
        _uiState.update {
            it.copy(
                pixelsPerSecond = clamped,
                document = it.document.copy(pixelsPerSecond = clamped),
            )
        }
    }

    /** Zoom step for the TopBar buttons — ~40% in per tap, ~30% out. */
    fun zoomIn() = setZoom(_uiState.value.pixelsPerSecond * 1.4f)
    fun zoomOut() = setZoom(_uiState.value.pixelsPerSecond * 0.7f)

    /**
     * Fit-all zoom: pick a pixels-per-second so the whole timeline fits comfortably in the visible
     * width (85% of the viewport, leaving a little breathing room on the right). No-op until the
     * viewport width has been reported by the UI ([setTimelineViewportPx]) and there's actually
     * content to fit — if either is missing, keep the current zoom.
     */
    fun fitZoomToTimeline() {
        val totalSec = document.totalDurationMs / 1000f
        if (totalSec <= 0f || timelineViewportPx <= 0f) return
        setZoom((timelineViewportPx * 0.85f) / totalSec)
    }

    private fun clampPps(pxPerSec: Float): Float {
        val maxPps = 500f
        val totalSec = document.totalDurationMs / 1000f
        // Zoom-out limit: the whole project fits within 2/3 of the visible timeline width.
        val minPps = if (totalSec > 0f && timelineViewportPx > 0f) {
            ((timelineViewportPx * 2f / 3f) / totalSec).coerceIn(0.1f, maxPps)
        } else 2f
        return pxPerSec.coerceIn(minPps, maxPps)
    }

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
     * The clip Crop & Transform edits. Not [EditorUiState.selectedClipId] — that's
     * `selectedClipIds.singleOrNull()`, which is `null` the moment a selection is more than one
     * clip, and selecting an imported video's picture clip also selects its linked shadow audio
     * clip (same `groupId`, expanded by [expandGroups]) — the single most common case in the app.
     * Crop has nothing to do to an audio clip, so the real target is the first non-audio clip in
     * the selection, falling back to whatever's selected if it's audio-only.
     */
    private fun cropTargetClipId(): String? {
        val sel = _uiState.value.selectedClips
        return (sel.firstOrNull { it.type != ClipType.AUDIO } ?: sel.firstOrNull())?.id
    }

    /**
     * Crop tool: scale/move the selected clip directly on the preview. [zoom] is a pinch
     * factor; [panXFrac]/[panYFrac] are drag deltas as a fraction of the preview size.
     */
    fun transformSelectedClip(zoom: Float, panXFrac: Float, panYFrac: Float, rotationDelta: Float = 0f) {
        val id = cropTargetClipId() ?: return
        updateClip(id) {
            it.copy(
                scale = (it.scale * zoom).coerceIn(0.1f, 6f),
                offsetX = (it.offsetX + panXFrac).coerceIn(-1.5f, 1.5f),
                offsetY = (it.offsetY + panYFrac).coerceIn(-1.5f, 1.5f),
                // Subtract: the gesture's rotation sign is opposite graphicsLayer.rotationZ, so adding
                // it spins the layer against the fingers. Negating makes the layer follow the twist.
                // Normalize to [-180,180) so it stays within KeyframeProperty.ROTATION's uiRange (the
                // inspector slider / keyframe envelope) instead of growing unbounded across twists.
                rotation = ((it.rotation - rotationDelta + 180f) % 360f + 360f) % 360f - 180f,
            )
        }
    }

    /**
     * Crop tool: snap the selected clip's scale/pan/rotation back to identity. The gesture in
     * [transformSelectedClip] is imprecise by nature (a pinch/drag/twist on a small preview), and
     * before this there was no way to undo an over-rotated or panned-off-frame clip except fighting
     * the same gesture back toward zero.
     */
    fun resetSelectedClipTransform() {
        val id = cropTargetClipId() ?: return
        updateClip(id) { it.copy(scale = 1f, offsetX = 0f, offsetY = 0f, rotation = 0f) }
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
            it.copy(
                document = doc,
                currentTimeMs = 0,
                isPlaying = false,
                selectedClipIds = emptyList(),
                promptHistory = doc.promptHistory, // restore the project's prompt history
                // Resume at the project's saved zoom. If nothing was persisted (new project /
                // legacy file), keep the current live pps — fitZoomToTimeline() will apply once
                // the viewport is measured (see setTimelineViewportPx below).
                pixelsPerSecond = doc.pixelsPerSecond ?: it.pixelsPerSecond,
                canUndo = false,
                canRedo = false,
            )
        }
        // If the loaded project doesn't have a persisted zoom, snap to fit-all once the viewport
        // is known (either now, if it's already been reported, or lazily via the setter below).
        if (doc.pixelsPerSecond == null && timelineViewportPx > 0f) {
            fitZoomToTimeline()
        }
    }
}
