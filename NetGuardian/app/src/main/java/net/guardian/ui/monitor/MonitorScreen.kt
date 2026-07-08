package net.guardian.ui.monitor

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.guardian.ServerUploader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class ContactInfo(
    val name: String,
    val phone: String,
    val email: String
)

private var mediaProjection: MediaProjection? = null

@Composable
fun MonitorScreen(activity: Activity) {
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var showContacts by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFilePath by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("") }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var photoFile by remember { mutableStateOf<File?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = photoFile
            if (file?.exists() == true) {
                scope.launch {
                    statusMsg = "Uploading photo..."
                    val ok = withContext(Dispatchers.IO) {
                        ServerUploader.uploadCapture("camera", file = file)
                    }
                    statusMsg = if (ok) "Photo uploaded" else "Upload failed"
                }
            }
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(result.resultCode, result.data!!)
            scope.launch {
                statusMsg = "Capturing screen..."
                val file = withContext(Dispatchers.IO) {
                    captureScreenshot(activity, mediaProjection)
                }
                if (file != null) {
                    val ok = withContext(Dispatchers.IO) {
                        ServerUploader.uploadCapture("screen", file = file)
                    }
                    statusMsg = if (ok) "Screenshot uploaded" else "Upload failed"
                    file.delete()
                } else {
                    statusMsg = "Screen capture failed"
                }
                mediaProjection?.stop()
                mediaProjection = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Device Sensors", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        if (statusMsg.isNotEmpty()) {
            item {
                Text(statusMsg, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }
        }

        // Camera
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) { Text("📷", fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text("Camera", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    val file = File(
                                        activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                                        "capture_${System.currentTimeMillis()}.jpg"
                                    )
                                    val uri = FileProvider.getUriForFile(activity,
                                        "${activity.packageName}.fileprovider", file)
                                    photoFile = file
                                    photoUri = uri
                                    cameraLauncher.launch(uri)
                                } else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Capture & Upload", fontSize = 13.sp) }
                        Text("Take photo, upload to server",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically))
                    }
                }
            }
        }

        // Microphone
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) { Text("🎤", fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text("Microphone", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    if (!isRecording) {
                                        try {
                                            val file = File(
                                                activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
                                                "recording_${System.currentTimeMillis()}.mp3"
                                            )
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
                                            Toast.makeText(activity, "Recording started", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(activity, "Mic error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        recorder?.apply {
                                            try { stop() } catch (_: Exception) {}
                                            release()
                                        }
                                        recorder = null
                                        isRecording = false
                                        Toast.makeText(activity, "Recording stopped", Toast.LENGTH_SHORT).show()
                                        audioFilePath?.let { path ->
                                            val f = File(path)
                                            if (f.exists()) {
                                                scope.launch {
                                                    statusMsg = "Uploading audio..."
                                                    val ok = withContext(Dispatchers.IO) {
                                                        ServerUploader.uploadCapture("audio", file = f)
                                                    }
                                                    statusMsg = if (ok) "Audio uploaded" else "Upload failed"
                                                }
                                            }
                                        }
                                    }
                                } else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isRecording) "Stop & Upload" else "Record", fontSize = 13.sp)
                        }
                        Text("Record audio and upload to server",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically))
                    }
                }
            }
        }

        // Screen Capture
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) { Text("🖥️", fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text("Screen Capture", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                if (mgr is MediaProjectionManager) {
                                    screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
                                } else {
                                    Toast.makeText(activity, "Screen capture not supported", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Screenshot & Upload", fontSize = 13.sp) }
                        Text("Capture screen and upload to server",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically))
                    }
                }
            }
        }

        // Contacts
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) { Text("👤", fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Text("Contacts", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    showContacts = !showContacts
                                    if (showContacts) {
                                        contacts = readContacts(activity.contentResolver)
                                        scope.launch {
                                            statusMsg = "Uploading contacts..."
                                            val json = JSONArray().apply {
                                                contacts.forEach { c ->
                                                    put(JSONObject().apply {
                                                        put("name", c.name)
                                                        put("phone", c.phone)
                                                        put("email", c.email)
                                                    })
                                                }
                                            }.toString()
                                            val ok = withContext(Dispatchers.IO) {
                                                ServerUploader.uploadContacts(contactsJson = json)
                                            }
                                            statusMsg = if (ok) "${contacts.size} contacts uploaded" else "Upload failed"
                                        }
                                    }
                                } else contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                            },
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (showContacts) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (showContacts) "Hide" else "Read & Upload", fontSize = 13.sp)
                        }
                        Text("Read and upload contacts to server",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically))
                    }
                }
            }
        }

        if (showContacts) {
            item {
                Text("Contacts (${contacts.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground)
            }
            items(contacts.take(50)) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(contact.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(contact.phone, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace)
                        if (contact.email.isNotEmpty()) {
                            Text(contact.email, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

internal fun captureScreenshot(activity: Activity, projection: MediaProjection?): File? {
    if (projection == null) return null
    try {
        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 1)
        val vd = projection.createVirtualDisplay("screen_capture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null)

        Thread.sleep(500)
        val image = reader.acquireLatestImage()
        vd.release()

        if (image == null) {
            reader.close()
            return null
        }

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

        image.close()
        reader.close()

        val file = File(activity.cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        cropped.recycle()
        bitmap.recycle()
        return file
    } catch (_: Exception) {
        return null
    }
}

private fun readContacts(resolver: ContentResolver): List<ContactInfo> {
    val list = mutableListOf<ContactInfo>()
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Email.ADDRESS
    )
    var cursor: Cursor? = null
    try {
        cursor = resolver.query(uri, projection, null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: "Unknown"
                val phone = it.getString(1) ?: ""
                val email = try { it.getString(2) ?: "" } catch (_: Exception) { "" }
                list.add(ContactInfo(name, phone, email))
            }
        }
    } catch (_: Exception) { } finally { cursor?.close() }
    return list
}
