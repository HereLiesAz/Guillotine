import sys

def main():
    file_path = r"C:\Users\azrie\StudioProjects\Guillotine\app\src\main\java\com\hereliesaz\guillotine\ui\NleScreen.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
        
    new_layout = """        var timelineWeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.4f) }

        androidx.compose.foundation.layout.BoxWithConstraints(
            androidx.compose.ui.Modifier
                .weight(1f - timelineWeight)
                .fillMaxWidth()
        ) {
            val isWide = maxWidth > maxHeight * 1.1f
            if (isWide) {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier
                            .weight(0.65f)
                            .fillMaxHeight()
                            .animateContentSize()
                    ) {
                        PreviewPlayer(
                            state,
                            androidx.compose.ui.Modifier.weight(1f).fillMaxWidth(),
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
                        modifier = androidx.compose.ui.Modifier
                            .weight(0.35f)
                            .fillMaxHeight()
                    )
                }
            } else {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().animateContentSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(0.5f).fillMaxWidth()) {
                        PreviewPlayer(
                            state,
                            androidx.compose.ui.Modifier.weight(1f).fillMaxWidth(),
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
                        modifier = androidx.compose.ui.Modifier
                            .weight(0.5f)
                            .fillMaxWidth()
                    )
                }
            }
        }
        
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        DraggableTimelineDivider(
            onDrag = { dragAmount ->
                timelineWeight = (timelineWeight - dragAmount * 0.0015f).coerceIn(0.2f, 0.8f)
            }
        )
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
        
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier
                .weight(timelineWeight)
                .fillMaxWidth()
        ) {
            EditorToolStrip(vm, state, onAnalyze, onTranscribe, providerLabel, { showSettings = true }, assistant = assistantState, onAgentInput = assistantVm::setInput, onAgentRun = { t -> assistantVm.run(t, sharedMcpTools, com.hereliesaz.guillotine.ai.agent.McpAgent.forSettings(context, settings, sharedMcpTools)) }, onImport = { importTargetTrack = null; importLauncher() }, onHelp = { showHelp = true }, onOpenStore = { showAzphaltStore = true }, asrModelPath = settings.asrModelPath)
            
            TimelinePanel(
                vm, state, onImportToTrack, onCreateOnTrack, 
                androidx.compose.ui.Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .animateContentSize()
            )
        }
"""
    
    # 0-indexed: lines 478 to 568 means indices 477 to 568 (since slice is exclusive of end index, we use 477:568)
    # Wait, the lines we viewed were 478 to 568. 
    # 478: if (widthClass == WindowWidthSizeClass.Expanded) {
    # 568: }
    # So indices 477 to 568 (568 is the element at index 568 which is line 569 "} // editor Column", we do NOT want to replace line 569).
    # Wait, `lines[477:568]` replaces elements from index 477 up to 567. That is 91 lines (478 through 568).
    
    # Let's verify line 477 is indeed the start
    if not lines[477].strip().startswith("if (widthClass == WindowWidthSizeClass.Expanded) {"):
        print(f"ERROR: Line 478 does not match! It is: {lines[477]}")
        sys.exit(1)
        
    lines[477:568] = [new_layout + "\n"]
    
    # Wait, AdvancedToolView inside the Row has some old clip tool buttons we promised to remove? No, we removed them earlier. Wait, let me just check.
    
    with open(file_path, "w", encoding="utf-8") as f:
        f.writelines(lines)
        
    print("Modifications applied successfully using line indices.")

if __name__ == "__main__":
    main()
