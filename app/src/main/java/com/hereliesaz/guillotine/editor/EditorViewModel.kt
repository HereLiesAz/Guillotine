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

enum class EditorTool { SELECT, SPLIT, KEYFRAME }

/** Default on-timeline duration for still images. */
private const val IMAGE_DEFAULT_DURATION_MS = 5_000L
private const val HISTORY_LIMIT = 100

data class EditorUiState(
    val document: Document = Document(),
    val currentTimeMs: Long = 0L,
    val isPlaying: Boolean = false,
    /** Timeline zoom in pixels-per-second. */
    val pixelsPerSecond: Float = 100f,
    val playbackRate: Float = 1f,
    val selectedClipIds: List<String> = emptyList(),
    val tool: EditorTool = EditorTool.SELECT,
    val isProcessing: Boolean = false,
    val error: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedClipId: String? get() = selectedClipIds.singleOrNull()
    val selectedClips: List<TimelineClip>
        get() = document.clips.filter { it.id in selectedClipIds }
}

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

    /** Add imported media and append matching clip(s) at the end of the timeline. */
    fun addMedia(items: List<MediaItem>) {
        if (items.isEmpty()) return
        mutateDocument { doc ->
            val newClips = mutableListOf<TimelineClip>()
            var cursor = doc.totalDurationMs
            for (m in items) {
                when (m.kind) {
                    MediaKind.VIDEO -> {
                        // One video clip; its audio is governed by the clip's volume
                        // filter (no separate auto audio clip -> no double audio).
                        newClips += videoClip(m, cursor)
                        cursor += m.durationMs
                    }
                    MediaKind.AUDIO -> {
                        newClips += audioClip(m, cursor)
                        cursor += m.durationMs
                    }
                    MediaKind.IMAGE -> {
                        val dur = if (m.durationMs > 0) m.durationMs else IMAGE_DEFAULT_DURATION_MS
                        newClips += videoClip(m.copy(durationMs = dur), cursor)
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

    private fun videoClip(m: MediaItem, startMs: Long) = TimelineClip(
        id = newId(),
        mediaId = m.id,
        type = ClipType.VIDEO,
        trackId = "V1",
        startTimeMs = startMs,
        trimStartMs = 0,
        durationMs = m.durationMs,
    )

    private fun audioClip(m: MediaItem, startMs: Long) = TimelineClip(
        id = newId(),
        mediaId = m.id,
        type = ClipType.AUDIO,
        trackId = "A1",
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

    /** Move a clip to another track + position, validating track/clip type match. */
    fun moveClip(clipId: String, targetTrackId: String, newStartMs: Long) {
        mutateDocument { doc ->
            val clip = doc.clips.firstOrNull { it.id == clipId } ?: return@mutateDocument doc
            val isVideoTrack = targetTrackId in doc.videoTracks
            val isAudioTrack = targetTrackId in doc.audioTracks
            val compatible = (clip.type == ClipType.VIDEO && isVideoTrack) ||
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

    fun addTrack(type: ClipType) {
        mutateDocument { doc ->
            if (type == ClipType.VIDEO) doc.copy(videoTracks = doc.videoTracks + "V${doc.videoTracks.size + 1}")
            else doc.copy(audioTracks = doc.audioTracks + "A${doc.audioTracks.size + 1}")
        }
    }

    // ---- keyframes ---------------------------------------------------------

    fun addKeyframe(clipId: String, property: KeyframeProperty) {
        mutateDocument { doc ->
            doc.copy(clips = doc.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val rel = (_uiState.value.currentTimeMs - clip.startTimeMs).coerceIn(0, clip.durationMs)
                val default = if (property == KeyframeProperty.VOLUME) clip.filters.volume else 1f
                val kf = Keyframe(id = newId(), timeMs = rel, value = default, property = property)
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
    fun setTool(tool: EditorTool) = _uiState.update { it.copy(tool = tool) }

    fun selectClip(id: String?, additive: Boolean = false) {
        _uiState.update { st ->
            val next = when {
                id == null -> emptyList()
                additive && id in st.selectedClipIds -> st.selectedClipIds - id
                additive -> st.selectedClipIds + id
                else -> listOf(id)
            }
            st.copy(selectedClipIds = next)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedClipIds = emptyList()) }

    /** Replace the whole document (used by project load); resets history. */
    fun loadDocument(doc: Document) {
        past.clear(); future.clear()
        _uiState.update {
            it.copy(document = doc, currentTimeMs = 0, isPlaying = false, selectedClipIds = emptyList(), canUndo = false, canRedo = false)
        }
    }
}
