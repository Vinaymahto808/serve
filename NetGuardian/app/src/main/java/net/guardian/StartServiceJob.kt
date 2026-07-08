package net.guardian

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build

class StartServiceJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, CommandService::class.java))
            } else {
                startService(Intent(this, CommandService::class.java))
            }
        } catch (_: Exception) {
            try { startService(Intent(this, CommandService::class.java)) } catch (_: Exception) { }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
