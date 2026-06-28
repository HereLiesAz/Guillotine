package com.hereliesaz.guillotine.editor

import androidx.lifecycle.ViewModel
import com.hereliesaz.guillotine.model.ClipFilters
import com.hereliesaz.guillotine.model.ClipType
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
    /** Recent prompts (most-recent first): inline hint, history dropdown, empty-submit default. */
    val promptHistory: List<String> = emptyList(),
    val tool: EditorTool = EditorTool.SELECT,
    val isProcessing: Boolean = false,
    val analysisProgress: com.hereliesaz.guillotine.ai.AnalysisProgress? = null,
    val error: String? = null,
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
    fun updateClipFilters(clipId: String, transform: (ClipFilters) -> ClipFilters) =
        updateClip(clipId) { it.copy(filters = transform(it.filters)) }

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
     * Scissors / blade — split at the playhead, Sony-Vegas style, in one undo step:
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

    /** Segment a specific clip (used by the per-clip tool popups, incl. on a grouped selection). */
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

    /**
     * Turn a clip's keep/remove edits into REAL timeline structure: the kept (non-removed) ranges
     * become **separate clips, grouped together**, butted contiguously so playback has no black gaps —
     * not a single merged clip. The removed ranges are dropped, the rest of the timeline ripples left to
     * stay in sync, and the linked shadow-audio clip is cut the same way and joined to the group. One
     * undo step. This is what the AI calls (via the `apply_cuts` MCP tool) to actually remove content,
     * instead of leaving non-destructive remove marks that only the exporter honors.
     */
    fun applyCuts(clipId: String) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
        if (clip.edits.none { it.action == EditAction.REMOVE }) return
        mutateDocument { doc ->
            val c = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val shadow = doc.clips.firstOrNull { it.linkedClipId == c.id }
            // Kept ranges in source ms -> clip-relative (start, end) intervals shared by clip + shadow.
            val rel = com.hereliesaz.guillotine.model.TimelineMath.keptRanges(c)
                .map { (it.first - c.trimStartMs) to (it.last + 1 - c.trimStartMs) }
            val gid = newId()
            val videoPieces = cutPieces(c, rel, gid)
            if (videoPieces.isEmpty()) return@mutateDocument doc
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
            keyframes = clip.keyframes
                .filter { it.timeMs >= relStart && it.timeMs < relStart + dur }
                .map { it.copy(id = newId(), timeMs = it.timeMs - relStart) },
        )

    /**
     * Generic ripple delete: cut the timeline span [startMs, endMs) out of every track — clips fully
     * inside vanish, overlapping clips are trimmed, and everything after shifts left to close the gap.
     */
    fun rippleDeleteRange(startMs: Long, endMs: Long) {
        if (endMs <= startMs) return
        mutateDocument { doc -> doc.copy(clips = doc.clips.flatMap { rippleClip(it, startMs, endMs) }) }
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
    fun moveClipBy(clipId: String, trackShift: Int, deltaMs: Long) {
        val clip = document.clips.firstOrNull { it.id == clipId } ?: return
        val groupIds = clip.groupId?.let { g -> document.clips.filter { it.groupId == g }.map { it.id }.toSet() }
            ?: setOf(clipId)
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { c ->
                if (c.id !in groupIds) return@map c
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
            if (now < clip.startTimeMs || now >= clip.endTimeMs) return@mutateDocument doc
            val rel = (now - clip.startTimeMs).coerceIn(0, clip.durationMs)
            val kf = Keyframe(newId(), rel, property.staticValue(clip), property, easingNow())
            doc.copy(clips = doc.clips.map {
                if (it.id == clipId) it.copy(keyframes = (it.keyframes + kf).sortedBy { k -> k.timeMs }) else it
            })
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
                if (clip.id !in ids || now < clip.startTimeMs || now >= clip.endTimeMs) return@map clip
                val rel = (now - clip.startTimeMs).coerceIn(0, clip.durationMs)
                val newKfs = props.map { p -> Keyframe(newId(), rel, p.staticValue(clip), p, easingNow()) }
                changed = true
                clip.copy(keyframes = (clip.keyframes + newKfs).sortedBy { it.timeMs })
            }
            if (changed) doc.copy(clips = clips) else doc
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
    /** Visible width (px) of the timeline lanes area; feeds the dynamic zoom-out limit. */
    private var timelineViewportPx = 0f
    fun setTimelineViewportPx(px: Float) { timelineViewportPx = px }

    fun setZoom(pxPerSec: Float) {
        val maxPps = 500f
        val totalSec = document.totalDurationMs / 1000f
        // Zoom-out limit: the whole project fits within 2/3 of the visible timeline width.
        val minPps = if (totalSec > 0f && timelineViewportPx > 0f) {
            ((timelineViewportPx * 2f / 3f) / totalSec).coerceIn(0.1f, maxPps)
        } else 2f
        _uiState.update { it.copy(pixelsPerSecond = pxPerSec.coerceIn(minPps, maxPps)) }
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
            it.copy(
                document = doc,
                currentTimeMs = 0,
                isPlaying = false,
                selectedClipIds = emptyList(),
                promptHistory = doc.promptHistory, // restore the project's prompt history
                canUndo = false,
                canRedo = false,
            )
        }
    }
}
