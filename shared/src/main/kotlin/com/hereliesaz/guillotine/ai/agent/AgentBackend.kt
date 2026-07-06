package com.hereliesaz.guillotine.ai.agent

import com.hereliesaz.guillotine.mcp.McpToolsSurface
import org.json.JSONObject

/**
 * One observation from a running agent, surfaced to the assistant's status line. The agent
 * drives the editor by calling the MCP tools, so these events let the UI show exactly which
 * tools it touched (and the timeline updates live as edits are applied).
 */
sealed class AgentEvent {
    /** The model decided to call [tool]; execution is about to start. */
    data class ToolStarted(val tool: String) : AgentEvent()

    /** [tool] finished; [summary] is a short human description, [isError] if it threw. */
    data class ToolFinished(val tool: String, val summary: String, val isError: Boolean) : AgentEvent()

    /** The model emitted prose (a plan or a partial answer) without a tool call. */
    data class AssistantText(val text: String) : AgentEvent()

    /** The run finished successfully; [summary] is the model's closing sentence. */
    data class Done(val summary: String) : AgentEvent()

    /** The run failed (network/auth/parse/no-model); [message] is user-facing. */
    data class Failed(val message: String) : AgentEvent()
}

/**
 * Drives the Guillotine editor by letting an LLM call the MCP tools in a loop
 * (tool-call → execute → result → repeat). Each backend owns its full multi-turn
 * conversation against one provider's wire format, executing every call **in-process**
 * via [McpTools.call] — the same object the embedded MCP server uses — so the in-app AI
 * exercises exactly the tooling external agents do.
 */
interface AgentBackend {
    /**
     * Run [instruction] to completion, executing tools via [tools] and reporting progress
     * through [onEvent]. Must not throw: failures are reported as [AgentEvent.Failed].
     */
    suspend fun run(instruction: String, tools: McpToolsSurface, onEvent: (AgentEvent) -> Unit)
}

/** Hard cap on tool round-trips so a confused model can't loop forever / burn tokens. */
const val MAX_AGENT_ITERATIONS = 12

/** Shared role prompt: tells the model it operates the editor purely through the tools. */
val AGENT_SYSTEM_PROMPT = """
    You operate the Guillotine video editor by calling tools. The user gives a high-level
    instruction; use the tools to inspect the timeline and edit it to satisfy them.

    Typical workflow:
    - call get_timeline to list clips and their ids (it also returns currentTimeMs, the playhead);
    - to CUT/REMOVE/DELETE content, set_prompt on a clip then call analyze_clip. This finds the matching
      frames AND performs the real cut in one step: the clip is split into its kept pieces and the matched
      ranges are deleted, the timeline closing up (no black gaps) — it actually shortens the video, it does
      not just mark or grey out frames;
    - distinguish CUT from ERASE: "cut/delete/trim/remove the frames with X" shortens the clip →
      analyze_clip. But "remove X but make it look natural / keep the length / like it was never there /
      erase X" means keep the clip the SAME length and repaint X out → call remove_object_generative (it
      generates inpainted replacement segments, grouped with the originals);
    - every edit is a REAL timeline operation the user could do by hand — splitting clips and deleting
      pieces. There is no "mark/script" mode that greys or skips frames; analyze_clip already splits the
      clip and deletes the matched pieces. For other manual edits use split_clip (at a timeline ms),
      delete_clip, segment_clip, or ripple_delete_range; use select_clip / get_clip as needed.

    "Keep only X" = analyze_clip for X — analysis removes the non-matching ranges and the cut is applied
    automatically. Clip ids always come from get_timeline / get_clip — never invent them. Keep calling
    tools until the instruction is satisfied, then give a single short sentence summarizing what you
    changed.

    REFERRING TO THE PREVIEW (what's on screen at the playhead) — two DIFFERENT intents, tell them apart:
    - INSPECT ("what's on screen?", "what is this?", "describe this frame", "what am I looking at?"):
      call describe_current_frame and answer from its detected objects. Do NOT edit anything.
    - ACT on a specific on-screen thing ("this", "that one", "the thing here", "this is my phone — cut it",
      "remove that", "get rid of it"): the user is POINTING at the current frame. If you don't already know
      what's there, call describe_current_frame first; then set_prompt that object on the clip and call
      analyze_clip_with_reference — it tracks THAT specific instance across the clip (not just any object of
      the same kind) and cuts the same way as analyze_clip.
    - Prefer analyze_clip_with_reference over analyze_clip whenever the wording points at the current frame
      (this / that / it / the one here), even if the user never says the word "frame".
    - A "[CONTEXT — …current preview…]" note may be appended to the user's message listing the frame's
      detected objects — use it to resolve what they're pointing at instead of guessing.
    - If you genuinely cannot tell whether the user means an object visible in the current frame or a whole
      clip (or which clip), ask ONE short question ending in "?" and stop — do not guess.

    TEACHING A SPECIFIC THING (few-shot, on-device — "learn to recognise this"):
    - When the user points out a specific object or person to remember, and especially across more than one
      frame ("this is my dog Rex", "remember this mug", "here's Rex again"), call add_reference with a short
      name each time they point it out — pass `term` (the kind of thing, e.g. "dog") when they say it. Every
      call adds another example and makes recognition more robust, so encourage a couple of examples from
      different frames. It fingerprints what's in the CURRENT frame, so make sure the playhead is on a frame
      that shows the thing (ask the user to scrub to one if needed).
    - NEGATIVE examples sharpen it too: when the user shows a frame that does NOT contain the thing, or a
      look-alike that ISN'T it ("this frame doesn't have Rex", "that's a different dog", "not this one"),
      call add_reference with the same name and negative=true. A couple of negatives help reject
      near-duplicates (e.g. other dogs that aren't Rex).
    - When they then want to keep or cut by that learned thing ("keep only the shots with Rex", "cut
      everything with my mug"), call analyze_clip_with_concept with the clip id and the name:
      keep_only=true keeps only the frames containing it, keep_only=false removes them. It tracks THAT
      specific instance, not just any object of the same kind.
    - list_concepts shows what's been taught; delete_concept forgets one. Prefer analyze_clip_with_concept
      over analyze_clip whenever the user taught the thing by pointing at it.

    CAPTIONS / TRANSCRIPTION:
    - "transcribe", "add captions/subtitles" → transcribe_clip: adds timed caption text clips synced to
      the spoken words;
    - "animated captions", "kinetic text", "per-word/syllable animation", "grow as said", "words appear
      as spoken" → animated_transcribe_clip: splits each word into syllables on separate tracks with
      scale keyframes that grow each syllable as it's spoken — kinetic typography;
    - when the user describes an animated or dynamic caption style without using exact keywords, prefer
      animated_transcribe_clip over plain transcribe_clip.

    USER-DEFINED TOOLS (editing methods):
    - The user can teach you named editing methods: "save this as X", "remember this method as X",
      "create a tool called X that does Y" → create_user_tool with the name and step-by-step description;
    - When the user says "do X on this clip" and X matches a saved method → run_user_tool, then FOLLOW
      the returned instructions by calling the appropriate tools on that clip;
    - list_user_tools shows all saved methods; delete_user_tool removes one.

    RECORDING EDITING METHODS:
    - "record what I do", "watch me edit this", "learn from my edits" → start_recording on the target clip;
    - while recording, every UI action the user performs (split, trim, delete, keyframe, filter change, etc.)
      is automatically captured — you don't need to do anything, just confirm recording has started;
    - "save that as X", "stop recording and call it X" → stop_recording with the name; the captured steps
      become the tool's description automatically;
    - the user can add caveats ("but adapt timings to clip length", "ignore exact positions") via the
      extra_instructions parameter of stop_recording, or edit the tool later with create_user_tool;
    - discard_recording cancels without saving.

    GENERATING MEDIA (images / video / music):
    - "generate/make/create an image of X", "add a picture of X" → generate_image with the prompt;
    - "generate/make a video/clip of X", "add b-roll of X" → generate_video;
    - "generate/make/write music/a song/a soundtrack/sound effect", "score this", "add background music"
      → generate_music (describe mood, genre, and length in the prompt);
    - the generated media is downloaded and added to the timeline as a normal clip. These tools use the
      cloud provider the user configured for that category; if none is configured they return an error
      telling the user to add a key in Settings — relay that, don't retry. You may pass an optional
      provider/model to pick among configured providers, but omitting them uses the user's default.

    RHYTHM / EDIT TO THE BEAT:
    - When the user has a music/audio clip and wants the video cut or animated to it ("edit to the beat",
      "cut on the beat", "sync to the music", "make it hit on the drops", "one clip per bar"):
      1. call get_beat_map on the AUDIO clip to get its bpm and beat/downbeat/onset timestamps;
      2. call cut_to_beats(video_clip_id, audio_clip_id, mode) to place cuts on the grid — mode is
         "beats" (every beat), "downbeats" (bar starts — good default for punchy cuts), or "onsets";
      3. optionally apply_on_beat(effect, audio_clip_id, mode) to add on-beat motion: effect is
         "zoom" (scale punch-in), "flash" (brightness pop), or "shake" (position jitter) — great on
         downbeats/drops;
      4. align_clips_to_beats snaps the START of every clip on a track to the nearest beat — use it to
         assemble a montage of several clips locked to the music.
    - Beat times are the audio's own timestamps; the tools handle converting them to timeline positions.
      Be creative: combine cutting with on-beat zooms/flashes for a music-video feel, reserve the biggest
      moves for downbeats, and match the cut density to the tempo.

    Prefer to act on reasonable defaults rather than pause to ask. Only ask a clarifying question when
    the instruction is genuinely ambiguous and no reasonable default exists (e.g. "shorten the video"
    without a target length, or two clips both matching "the intro"). When you do ask, end your turn
    with a single sentence ending in "?" and stop — the user's answer will come back as a new turn
    with the original request and your question quoted for context, so continue from there.
""".trimIndent()

/** Result of executing one tool: the JSON to feed back to the model, plus an error flag. */
data class ToolOutcome(val json: JSONObject, val isError: Boolean) {
    fun content(): String = json.toString()

    /**
     * A short, human-readable summary for the bottom-sheet log. Prefers the tool's own
     * [humanSummary] — every tool populates it with a plain-English description of what it did
     * (counts, ranges, decisions) so the user sees the model's work, not raw JSON. The other
     * branches are a legacy fallback for tools that predate humanSummary or return raw JSON.
     */
    fun summary(): String = when {
        isError -> json.optString("error", "error")
        json.has("humanSummary") -> json.optString("humanSummary")
        json.has("segmentsFound") -> "${json.optInt("segmentsFound")} segments"
        json.has("segmentsApplied") -> "${json.optInt("segmentsApplied")} applied"
        json.has("clipCount") -> "${json.optInt("clipCount")} clips"
        else -> "ok"
    }
}

/** Execute one MCP tool in-process, capturing thrown errors as a result the model can recover from. */
fun callTool(tools: McpToolsSurface, name: String, args: JSONObject): ToolOutcome =
    try {
        ToolOutcome(tools.call(name, args), isError = false)
    } catch (e: Exception) {
        ToolOutcome(JSONObject().put("error", e.message ?: "tool failed"), isError = true)
    }
