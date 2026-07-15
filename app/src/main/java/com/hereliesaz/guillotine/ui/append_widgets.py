import sys

def main():
    file_path = r"C:\Users\azrie\StudioProjects\Guillotine\app\src\main\java\com\hereliesaz\guillotine\ui\Widgets.kt"
    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    imports = """
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
"""
    
    # Insert imports right after the package declaration
    if "import androidx.compose.foundation.gestures.detectVerticalDragGestures" not in content:
        content = content.replace("package com.hereliesaz.guillotine.ui\n", "package com.hereliesaz.guillotine.ui\n" + imports)
        
    divider_code = """
@Composable
fun DraggableTimelineDivider(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onDrag: (Float) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(androidx.compose.ui.graphics.Color(0xFF171717))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth(0.15f)
                .height(4.dp)
                .background(androidx.compose.ui.graphics.Color(0xFF525252), RoundedCornerShape(2.dp))
                .align(androidx.compose.ui.Alignment.Center)
        )
    }
}
"""

    if "fun DraggableTimelineDivider" not in content:
        content += "\n" + divider_code

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

if __name__ == "__main__":
    main()
