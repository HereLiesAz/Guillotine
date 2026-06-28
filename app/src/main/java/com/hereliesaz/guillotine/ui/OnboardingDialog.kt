package com.hereliesaz.guillotine.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.hereliesaz.guillotine.ai.agent.BundledModelExtractor
import com.hereliesaz.guillotine.ai.agent.ModelDownloadManager
import com.hereliesaz.guillotine.ai.agent.OnDeviceModel
import com.hereliesaz.guillotine.ai.agent.RECOMMENDED_ON_DEVICE_MODELS
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import com.hereliesaz.guillotine.ui.theme.Neutral800
import com.hereliesaz.guillotine.ui.theme.Neutral900
import com.hereliesaz.guillotine.ui.theme.Red500
import com.hereliesaz.guillotine.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen, non-dismissable onboarding dialog shown on first launch. Three steps:
 * 0 → notification permission, 1 → model selection, 2 → download progress (if needed).
 */
@Composable
fun OnboardingDialog(onComplete: (selectedModelPath: String) -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    // Path of the bundled model once extracted (null while extracting).
    var bundledPath by remember { mutableStateOf<String?>(null) }
    // Currently selected model path (starts as bundled once available).
    var selectedModelPath by remember { mutableStateOf("") }
    // Which model the user wants to download (null if they picked an installed one).
    var downloadModel by remember { mutableStateOf<OnDeviceModel?>(null) }

    // Extract bundled model in background.
    LaunchedEffect(Unit) {
        val path = withContext(Dispatchers.IO) { BundledModelExtractor.ensureExtracted(context) }
        bundledPath = path
        if (selectedModelPath.isBlank()) selectedModelPath = path
    }

    Dialog(
        onDismissRequest = { /* non-dismissable */ },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Neutral900)
                .systemBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(current = step, total = 3)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> PermissionsStep(onNext = { step = 1 })
                    1 -> ModelSelectionStep(
                        bundledPath = bundledPath,
                        selectedModelPath = selectedModelPath,
                        onSelect = { path, model ->
                            selectedModelPath = path
                            downloadModel = model
                        },
                    )
                    2 -> DownloadStep(
                        model = downloadModel!!,
                        bundledPath = bundledPath.orEmpty(),
                        onPathUpdate = { selectedModelPath = it },
                        onComplete = { onComplete(selectedModelPath) },
                        onFallback = {
                            selectedModelPath = bundledPath.orEmpty()
                            onComplete(bundledPath.orEmpty())
                        },
                    )
                }
            }

            // Bottom navigation buttons (step 1 and 2 handle their own).
            if (step == 1) {
                ModelSelectionButtons(
                    downloadModel = downloadModel,
                    selectedModelPath = selectedModelPath,
                    bundledPath = bundledPath,
                    onUseBundled = { onComplete(bundledPath.orEmpty()) },
                    onDownload = { step = 2 },
                    onContinue = { onComplete(selectedModelPath) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 0: Permissions
// ---------------------------------------------------------------------------

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> onNext() }

    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    Text("Welcome to Guillotine", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(
        "Before you start editing, a couple of things to set up.",
        color = Neutral400, fontSize = 13.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text("Notifications", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "Guillotine runs video exports and AI analysis in the background. " +
            "Notifications let you see progress and cancel these operations even when " +
            "the app is minimized.",
        color = Neutral400, fontSize = 12.sp,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (needsPermission) {
            Text(
                "Skip", color = Neutral500, fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onNext() }
                    .padding(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                colors = ButtonDefaults.buttonColors(containerColor = Red500),
            ) { Text("Allow notifications", fontSize = 12.sp, color = White) }
        } else {
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = Red500),
            ) { Text("Continue", fontSize = 12.sp, color = White) }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1: Model selection
// ---------------------------------------------------------------------------

@Composable
private fun ModelSelectionStep(
    bundledPath: String?,
    selectedModelPath: String,
    onSelect: (path: String, downloadModel: OnDeviceModel?) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Text("Choose your AI model", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(
        "A starter model is already installed so you can begin right away. " +
            "Download a larger model for better results, or change your mind later in Settings.",
        color = Neutral400, fontSize = 12.sp,
    )
    Spacer(Modifier.height(4.dp))

    RECOMMENDED_ON_DEVICE_MODELS.forEach { model ->
        val installed = if (model.bundled) bundledPath else ModelDownloadManager.installedPath(context, model)
        val isInstalled = installed != null
        val isSelected = isInstalled && installed == selectedModelPath

        ModelCard(
            model = model,
            isInstalled = isInstalled,
            isSelected = isSelected,
            onClick = {
                when {
                    isInstalled -> onSelect(installed.orEmpty(), null)
                    model.gated -> uriHandler.openUri(model.repoUrl)
                    else -> onSelect("", model)  // needs download
                }
            },
        )
    }
}

@Composable
private fun ModelSelectionButtons(
    downloadModel: OnDeviceModel?,
    selectedModelPath: String,
    bundledPath: String?,
    onUseBundled: () -> Unit,
    onDownload: () -> Unit,
    onContinue: () -> Unit,
) {
    Text(
        "You can always change your model in Settings.",
        color = Neutral500, fontSize = 10.sp,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (bundledPath != null) {
            Text(
                "Use starter model", color = Neutral400, fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onUseBundled() }
                    .padding(12.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (downloadModel != null) {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(containerColor = Red500),
            ) {
                Text(
                    "Download ${downloadModel.sizeLabel}",
                    fontSize = 12.sp, color = White,
                )
            }
        } else if (selectedModelPath.isNotBlank()) {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Red500),
            ) { Text("Continue", fontSize = 12.sp, color = White) }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: Download progress
// ---------------------------------------------------------------------------

@Composable
private fun DownloadStep(
    model: OnDeviceModel,
    bundledPath: String,
    onPathUpdate: (String) -> Unit,
    onComplete: () -> Unit,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    val downloadState by ModelDownloadManager.state.collectAsState()

    // Start the download when this step first composes.
    LaunchedEffect(model.id) {
        ModelDownloadManager.start(context, model)
    }

    Text("Downloading ${model.label}", color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Text(model.sizeLabel, color = Neutral500, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))

    when (val s = downloadState) {
        is ModelDownloadManager.DownloadState.Downloading -> {
            if (s.modelId == model.id) {
                LinearProgressIndicator(
                    progress = { s.fraction },
                    color = Red500,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(s.fraction * 100).toInt()}% downloaded",
                    color = Neutral400, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        is ModelDownloadManager.DownloadState.Done -> {
            if (s.modelId == model.id) {
                onPathUpdate(s.path)
                Text("Download complete!", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onComplete,
                    colors = ButtonDefaults.buttonColors(containerColor = Red500),
                ) { Text("Get started", fontSize = 12.sp, color = White) }
            }
        }
        is ModelDownloadManager.DownloadState.Failed -> {
            if (s.modelId == model.id) {
                Text(s.message, color = Red500, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Retry", color = Red500, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { ModelDownloadManager.start(context, model) },
                    )
                    Text(
                        "Use starter model instead", color = Neutral400, fontSize = 12.sp,
                        modifier = Modifier.clickable { onFallback() },
                    )
                }
            }
        }
        else -> {
            // Idle — download hasn't started yet or was cancelled elsewhere.
            LinearProgressIndicator(
                color = Red500,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Preparing download…", color = Neutral400, fontSize = 12.sp)
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "You can always change your model in Settings.",
            color = Neutral500, fontSize = 10.sp,
        )
        if (downloadState is ModelDownloadManager.DownloadState.Downloading) {
            Text(
                "Cancel", color = Neutral400, fontSize = 12.sp,
                modifier = Modifier
                    .clickable {
                        ModelDownloadManager.cancel()
                        onFallback()
                    }
                    .padding(start = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------

@Composable
private fun ModelCard(
    model: OnDeviceModel,
    isInstalled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = if (isSelected) Red500 else Neutral800,
                shape = RoundedCornerShape(8.dp),
            )
            .background(if (isSelected) Red500.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${model.sizeLabel} · ${model.license}", color = Neutral500, fontSize = 10.sp)
            }
            val badgeColor = Color(0xFF4ADE80) // green-400
            when {
                model.bundled && isInstalled -> Text("Ready", color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                isInstalled -> Text("Installed", color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                model.gated -> Text("HF sign-in ↗", color = Neutral500, fontSize = 11.sp)
                else -> Text(model.sizeLabel, color = Red500, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (model.abilities.isNotBlank()) {
            Text(
                model.abilities, color = Neutral400, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (model.limitations.isNotBlank()) {
            Text(
                model.limitations, color = Neutral500, fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) White else Neutral500),
            )
            if (i < total - 1) Spacer(Modifier.width(6.dp))
        }
    }
}
