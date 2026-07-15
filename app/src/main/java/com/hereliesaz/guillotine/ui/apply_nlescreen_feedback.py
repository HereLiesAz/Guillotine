import re

def main():
    file_path = r"C:\Users\azrie\StudioProjects\Guillotine\app\src\main\java\com\hereliesaz\guillotine\ui\NleScreen.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()
    
    # Add imports
    imports = """
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
"""
    if "import androidx.compose.foundation.layout.BoxWithConstraints" not in content:
        content = re.sub(r"(import androidx.compose.material3.Text\n)", r"\1" + imports.strip() + "\n", content, count=1)
        
    old_layout = """        if (widthClass == WindowWidthSizeClass.Expanded) {
            Column(Modifier.weight(0.6f).fillMaxWidth()) {
                PreviewPlayer(
                    state,
                    Modifier.weight(1f).fillMaxWidth(),
                    cropMode = state.tool == EditorTool.CROP,
                    showSafeZones = state.tool == EditorTool.CROP,
                    onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                )
                TransportControls(vm, state)
            }
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = settings.asrModelPath)
            TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.4f).fillMaxWidth())
        } else {
            PreviewPlayer(
                state,
                Modifier.weight(0.42f).fillMaxWidth(),
                cropMode = state.tool == EditorTool.CROP,
                showSafeZones = state.tool == EditorTool.CROP,
                onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
            )
            TransportControls(vm, state)
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = settings.asrModelPath)
            TimelinePanel(vm, state, onImportToTrack, onCreateOnTrack, Modifier.weight(0.58f).fillMaxWidth())
        }"""
    
    new_layout = """        var timelineWeight by remember { androidx.compose.runtime.mutableStateOf(0.4f) }
        
        Column(Modifier.weight(1f - timelineWeight).fillMaxWidth()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth > maxHeight * 1.1f
                if (isWide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(0.65f).fillMaxHeight()) {
                            PreviewPlayer(
                                state,
                                Modifier.weight(1f).fillMaxWidth(),
                                cropMode = state.tool == EditorTool.CROP,
                                showSafeZones = state.tool == EditorTool.CROP,
                                onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                            )
                            TransportControls(vm, state)
                        }
                        AdvancedToolView(
                            vm = vm,
                            state = state,
                            onTranscribe = onTranscribe,
                            modifier = Modifier.weight(0.35f).fillMaxHeight()
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(0.5f).fillMaxWidth()) {
                            PreviewPlayer(
                                state,
                                Modifier.weight(1f).fillMaxWidth(),
                                cropMode = state.tool == EditorTool.CROP,
                                showSafeZones = state.tool == EditorTool.CROP,
                                onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                            )
                            TransportControls(vm, state)
                        }
                        AdvancedToolView(
                            vm = vm,
                            state = state,
                            onTranscribe = onTranscribe,
                            modifier = Modifier.weight(0.5f).fillMaxWidth()
                        )
                    }
                }
            }
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = settings.asrModelPath)
        }
        
        DraggableTimelineDivider(
            onDrag = { dragAmount ->
                timelineWeight = (timelineWeight - dragAmount * 0.0015f).coerceIn(0.2f, 0.8f)
            }
        )
        
        TimelinePanel(
            vm, state, onImportToTrack, onCreateOnTrack, 
            Modifier.weight(timelineWeight).fillMaxWidth()
        )"""
        
    content = content.replace(old_layout, new_layout)
    
    # Remove ClipToolButtons from EditorToolStrip
    content = content.replace("ClipToolButtons(vm, state, onTranscribe)", "// Removed inline clip tools because they are now in AdvancedToolView")

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)
        
    print("Modifications applied successfully.")

if __name__ == "__main__":
    main()
