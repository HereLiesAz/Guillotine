import re
import sys

def main():
    file_path = r"C:\Users\azrie\StudioProjects\Guillotine\app\src\main\java\com\hereliesaz\guillotine\ui\NleScreen.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()
    
    # 1. Add imports
    imports = """
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
"""
    if "import androidx.compose.animation.AnimatedVisibility" not in content:
        content = re.sub(r"(import androidx.compose.material3.Text\n)", r"\1" + imports.strip() + "\n", content, count=1)
        
    # 2. Layout substitution
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
    
    new_layout = """        if (widthClass == WindowWidthSizeClass.Expanded) {
            Row(
                Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .animateContentSize()
                ) {
                    PreviewPlayer(
                        state,
                        Modifier.weight(1f).fillMaxWidth(),
                        cropMode = state.tool == EditorTool.CROP,
                        showSafeZones = state.tool == EditorTool.CROP,
                        onCropTransform = { z, x, y, r -> vm.transformSelectedClip(z, x, y, r) },
                    )
                    TransportControls(vm, state)
                }

                AnimatedVisibility(
                    visible = state.tool != EditorTool.SELECT || state.selectedClips.isNotEmpty(),
                    enter = slideInHorizontally(initialOffsetX = { it }) + expandHorizontally(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + shrinkHorizontally()
                ) {
                    AdvancedToolView(
                        vm = vm,
                        state = state,
                        onTranscribe = onTranscribe,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                    )
                }
            }
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = settings.asrModelPath)
            
            // The timeline "swells" (takes more space) when a clip is selected.
            val timelineWeight = if (state.selectedClips.isNotEmpty()) 0.5f else 0.4f
            TimelinePanel(
                vm, state, onImportToTrack, onCreateOnTrack, 
                Modifier
                    .weight(timelineWeight)
                    .fillMaxWidth()
                    .animateContentSize()
            )
        } else {
            Column(
                Modifier
                    .weight(0.42f)
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
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
            
            AnimatedVisibility(
                visible = state.tool != EditorTool.SELECT || state.selectedClips.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                AdvancedToolView(
                    vm = vm,
                    state = state,
                    onTranscribe = onTranscribe,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
            
            val timelineWeight = if (state.selectedClips.isNotEmpty()) 0.68f else 0.58f
            TimelinePanel(
                vm, state, onImportToTrack, onCreateOnTrack, 
                Modifier
                    .weight(timelineWeight)
                    .fillMaxWidth()
                    .animateContentSize()
            )
        }"""
        
    content = content.replace(old_layout, new_layout)
    
    # 3. Remove ClipToolButtons
    content = content.replace("ClipToolButtons(vm, state, onTranscribe)", "// Removed inline clip tools because they are now in AdvancedToolView")

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)
        
    print("Modifications applied successfully.")

if __name__ == "__main__":
    main()
