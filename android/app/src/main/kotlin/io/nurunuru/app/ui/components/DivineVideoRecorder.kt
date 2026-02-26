package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executor

data class RecordedVideo(
    val uri: Uri,
    val url: String,
    val mimeType: String,
    val size: Long,
    val proofTags: List<List<String>> = emptyList()
)

@Composable
fun DivineVideoRecorder(
    onComplete: (RecordedVideo) -> Unit,
    onClose: () -> Unit,
    repository: NostrRepository
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("カメラとマイクの権限が必要です", color = Color.White)
        }
        return
    }

    val previewView = remember { PreviewView(context) }
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        VideoCapture.withOutput(recorder)
    }
    var recording: Recording? by remember { mutableStateOf(null) }
    var recordingState by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var isUploading by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )
        } catch (e: Exception) {
            Log.e("DivineVideoRecorder", "Use case binding failed", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.2f))
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(nuruColors.lineGreen)
            )
        }

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, enabled = !isUploading) {
                Icon(NuruIcons.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text("6.3秒ループ動画", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(
                onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT },
                enabled = !recordingState && !isUploading
            ) {
                Icon(Icons.Default.Cached, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // Footer / Record Button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isUploading) {
                Text(
                    text = if (recordingState) "録画中..." else "ボタンを長押しして録画",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(if (recordingState) nuruColors.lineGreen else Color.White.copy(alpha = 0.2f))
                        .border(4.dp, Color.White, CircleShape)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (isUploading) continue

                                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                        val file = File(context.cacheDir, "divine_loop.mp4")
                                        val outputOptions = FileOutputOptions.Builder(file).build()

                                        val activeRecording = videoCapture.output
                                            .prepareRecording(context, outputOptions)
                                            .apply {
                                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                    withAudioEnabled()
                                                }
                                            }
                                            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                                                if (recordEvent is VideoRecordEvent.Finalize) {
                                                    recordingState = false
                                                    if (recordEvent.hasError()) {
                                                        Log.e("DivineVideoRecorder", "Video capture failed", recordEvent.cause)
                                                        progress = 0f
                                                    } else {
                                                        scope.launch {
                                                            isUploading = true
                                                            val uri = Uri.fromFile(file)
                                                            val bytes = file.readBytes()
                                                            val url = repository.uploadImage(bytes, "video/mp4")
                                                            if (url != null) {
                                                                onComplete(RecordedVideo(uri, url, "video/mp4", file.length()))
                                                            } else {
                                                                isUploading = false
                                                                progress = 0f
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                        recording = activeRecording
                                        recordingState = true
                                        val startTime = System.currentTimeMillis()

                                        scope.launch {
                                            while (recordingState) {
                                                val elapsed = System.currentTimeMillis() - startTime
                                                progress = (elapsed / 6300f).coerceIn(0f, 1f)
                                                if (elapsed >= 6300) {
                                                    activeRecording.stop()
                                                    break
                                                }
                                                delay(30)
                                            }
                                        }
                                    } else if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                                        recording?.stop()
                                        recording = null
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (recordingState) 32.dp else 60.dp)
                            .clip(if (recordingState) RoundedCornerShape(4.dp) else CircleShape)
                            .background(if (recordingState) nuruColors.lineGreen else Color.Red)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("最大 6.3秒", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            } else {
                CircularProgressIndicator(color = nuruColors.lineGreen)
                Spacer(Modifier.height(16.dp))
                Text("動画を処理中...", color = Color.White)
            }
        }
    }
}
