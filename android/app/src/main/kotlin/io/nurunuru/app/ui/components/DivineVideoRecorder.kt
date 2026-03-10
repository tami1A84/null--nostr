/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Based on Divine mobile (https://github.com/verse-app/divine)
 * Copyright (c) Divine contributors
 */
package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
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
import io.nurunuru.app.data.ProofModeManager
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

private const val TAG = "DivineVideoRecorder"

data class RecordedVideo(
    val uri: Uri,
    val url: String,
    val thumbnailUrl: String?,
    val mimeType: String,
    val size: Long,
    val durationSeconds: Int,
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
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraOk = permissions[Manifest.permission.CAMERA] == true
        val audioOk = permissions[Manifest.permission.RECORD_AUDIO] == true
        hasAudioPermission = audioOk
        hasCameraPermission = cameraOk && audioOk
        Log.d(TAG, "Permissions: camera=$cameraOk, audio=$audioOk")
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

    // ── ProofMode ─────────────────────────────────────────────────────────────
    val proofModeManager = remember { ProofModeManager(context) }
    // Use a ref so the scope.launch coroutine always sees the latest session
    val proofSessionRef = remember { mutableStateOf<ProofModeManager.ProofSession?>(null) }
    val sensorReadings = remember { mutableListOf<ProofModeManager.SensorReading>() }
    var recordingStartMs by remember { mutableStateOf(0L) }

    // Sensor listener
    val sensorListener = remember {
        object : SensorEventListener {
            @Volatile var accX = 0f; @Volatile var accY = 0f; @Volatile var accZ = 0f
            @Volatile var gyroX = 0f; @Volatile var gyroY = 0f; @Volatile var gyroZ = 0f
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> { accX = event.values[0]; accY = event.values[1]; accZ = event.values[2] }
                    Sensor.TYPE_GYROSCOPE    -> { gyroX = event.values[0]; gyroY = event.values[1]; gyroZ = event.values[2] }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    // ── Camera ────────────────────────────────────────────────────────────────
    val previewView = remember { PreviewView(context) }
    val videoCapture = remember {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        VideoCapture.withOutput(recorder)
    }
    var recording: Recording? by remember { mutableStateOf(null) }
    var recordingState by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf("動画を処理中...") }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    // Sensor collection while recording
    LaunchedEffect(Unit) {
        snapshotFlow { recordingState }.collectLatest { isRecording ->
            if (isRecording) {
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                    sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                while (true) {
                    sensorReadings.add(ProofModeManager.SensorReading(
                        timestamp = Instant.now().toString(),
                        accX = sensorListener.accX, accY = sensorListener.accY, accZ = sensorListener.accZ,
                        gyroX = sensorListener.gyroX, gyroY = sensorListener.gyroY, gyroZ = sensorListener.gyroZ
                    ))
                    delay(500)
                }
            } else {
                sensorManager.unregisterListener(sensorListener)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            sensorManager.unregisterListener(sensorListener)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Progress bar
        Box(
            modifier = Modifier.fillMaxWidth().height(4.dp)
                .background(Color.White.copy(alpha = 0.2f)).align(Alignment.TopCenter)
        ) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(nuruColors.lineGreen))
        }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, enabled = !isUploading) {
                Icon(NuruIcons.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text("6.3秒ループ動画", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                        CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
                },
                enabled = !recordingState && !isUploading
            ) {
                Icon(Icons.Default.Cached, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }

        // Record button
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isUploading) {
                Text(
                    text = if (recordingState) "録画中..." else "ボタンを長押しして録画",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Box(
                    modifier = Modifier
                        .size(80.dp).clip(CircleShape)
                        .background(if (recordingState) nuruColors.lineGreen else Color.White.copy(alpha = 0.2f))
                        .border(4.dp, Color.White, CircleShape)
                        .pointerInput(hasCameraPermission) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (isUploading) continue

                                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                        val file = File(context.cacheDir, "divine_loop_${System.currentTimeMillis()}.mp4")
                                        val outputOptions = FileOutputOptions.Builder(file).build()

                                        // Start ProofMode session
                                        val session = proofModeManager.startSession()
                                        proofSessionRef.value = session
                                        sensorReadings.clear()
                                        recordingStartMs = System.currentTimeMillis()

                                        // hasCameraPermission == true guarantees both CAMERA and RECORD_AUDIO are granted
                                        Log.d(TAG, "Recording start — audio=true proofSession=${session.sessionId}")

                                        val activeRecording = videoCapture.output
                                            .prepareRecording(context, outputOptions)
                                            .withAudioEnabled()
                                            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                                                if (recordEvent is VideoRecordEvent.Finalize) {
                                                    recordingState = false
                                                    if (recordEvent.hasError()) {
                                                        Log.e(TAG, "Video capture failed: ${recordEvent.cause?.message}")
                                                        progress = 0f
                                                    } else {
                                                        val capturedSession = proofSessionRef.value
                                                        val capturedReadings = sensorReadings.toList()
                                                        val startMs = recordingStartMs

                                                        scope.launch {
                                                            isUploading = true
                                                            try {
                                                                uploadStatus = "動画をアップロード中..."
                                                                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                                                                val url = repository.uploadImage(bytes, "video/mp4")
                                                                if (url == null) {
                                                                    Log.e(TAG, "Video upload failed")
                                                                    isUploading = false; progress = 0f; return@launch
                                                                }

                                                                // Thumbnail: extract first frame and upload
                                                                uploadStatus = "サムネイル生成中..."
                                                                val thumbUrl = withContext(Dispatchers.IO) {
                                                                    extractAndUploadThumbnail(file, repository)
                                                                }
                                                                Log.d(TAG, "Thumbnail: $thumbUrl")

                                                                // ProofMode finalization
                                                                uploadStatus = "ProofMode処理中..."
                                                                val endTime = Instant.now().toString()
                                                                val totalMs = System.currentTimeMillis() - startMs
                                                                val recMs = minOf(totalMs, 6300L)

                                                                val proofTags = if (capturedSession != null) {
                                                                    withContext(Dispatchers.IO) {
                                                                        proofModeManager.finalizeAndGetTags(
                                                                            session = capturedSession,
                                                                            videoFile = file,
                                                                            endTime = endTime,
                                                                            sensorReadings = capturedReadings,
                                                                            totalDurationMs = totalMs,
                                                                            recordingDurationMs = recMs
                                                                        )
                                                                    }
                                                                } else {
                                                                    Log.w(TAG, "ProofMode session is null, skipping")
                                                                    emptyList()
                                                                }

                                                                val level = proofTags
                                                                    .find { it.getOrNull(0) == "verification" }
                                                                    ?.getOrNull(1) ?: "none"
                                                                Log.d(TAG, "ProofMode done: ${proofTags.size} tags, level=$level")

                                                                val durationSec = (recMs / 1000).toInt().coerceAtLeast(1)

                                                                onComplete(RecordedVideo(
                                                                    uri = Uri.fromFile(file),
                                                                    url = url,
                                                                    thumbnailUrl = thumbUrl,
                                                                    mimeType = "video/mp4",
                                                                    size = file.length(),
                                                                    durationSeconds = durationSec,
                                                                    proofTags = proofTags
                                                                ))
                                                            } catch (e: Exception) {
                                                                Log.e(TAG, "Post-recording processing failed", e)
                                                                isUploading = false; progress = 0f
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                        recording = activeRecording
                                        recordingState = true

                                        scope.launch {
                                            val startTime = System.currentTimeMillis()
                                            while (recordingState) {
                                                val elapsed = System.currentTimeMillis() - startTime
                                                progress = (elapsed / 6300f).coerceIn(0f, 1f)
                                                if (elapsed >= 6300) { activeRecording.stop(); break }
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
                Text("最大 6.3秒 • ProofMode対応", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            } else {
                CircularProgressIndicator(color = nuruColors.lineGreen)
                Spacer(Modifier.height(16.dp))
                Text(uploadStatus, color = Color.White)
            }
        }
    }
}

/** Extract first video frame as JPEG and upload it. Returns the thumbnail URL or null. */
private suspend fun extractAndUploadThumbnail(
    videoFile: File,
    repository: NostrRepository
): String? = withContext(Dispatchers.IO) {
    try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoFile.absolutePath)
        val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()

        if (bitmap == null) return@withContext null

        val baos = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        bitmap.recycle()

        repository.uploadImage(baos.toByteArray(), "image/jpeg")
    } catch (e: Exception) {
        Log.w("DivineVideoRecorder", "Thumbnail generation failed: ${e.message}")
        null
    }
}
