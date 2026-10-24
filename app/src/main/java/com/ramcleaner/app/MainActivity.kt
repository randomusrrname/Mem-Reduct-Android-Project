package com.ramcleaner.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var tvPercent: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvResult: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnClean: MaterialButton

    private val handler = Handler(Looper.getMainLooper())

    // Actualiza los números de RAM cada 2 segundos mientras la app está abierta
    private val refreshTask = object : Runnable {
        override fun run() {
            updateMemoryInfo()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPercent = findViewById(R.id.tvPercent)
        tvDetails = findViewById(R.id.tvDetails)
        tvResult = findViewById(R.id.tvResult)
        progress = findViewById(R.id.progress)
        btnClean = findViewById(R.id.btnClean)

        btnClean.setOnClickListener { cleanRam() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    private fun readMemory(): ActivityManager.MemoryInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }

    private fun formatSize(bytes: Long): String =
        Formatter.formatShortFileSize(this, bytes)

    private fun updateMemoryInfo() {
        val info = readMemory()
        val used = info.totalMem - info.availMem
        val percent = (used * 100 / info.totalMem).toInt()

        tvPercent.text = "$percent %"
        progress.setProgressCompat(percent, true)
        tvDetails.text = getString(
            R.string.details,
            formatSize(used),
            formatSize(info.availMem),
            formatSize(info.totalMem)
        )
    }

    private fun cleanRam() {
        btnClean.isEnabled = false
        tvResult.text = getString(R.string.cleaning)

        val availableBefore = readMemory().availMem
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager
        val myPackage = packageName

        Thread {
            var checked = 0

            // Recorre las apps instaladas (menos las del sistema y esta misma)
            // y le pide a Android que cierre sus procesos en segundo plano.
            for (app in pm.getInstalledApplications(0)) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystem && app.packageName != myPackage) {
                    am.killBackgroundProcesses(app.packageName)
                    checked++
                }
            }

            // Esperamos un momento para que Android libere la memoria
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }

            handler.post {
                if (isFinishing || isDestroyed) return@post

                val freed = readMemory().availMem - availableBefore
                tvResult.text = if (freed > 0) {
                    getString(R.string.result_freed, formatSize(freed), checked)
                } else {
                    getString(R.string.result_nothing, checked)
                }
                updateMemoryInfo()
                btnClean.isEnabled = true
            }
        }.start()
    }
}
