package com.ramcleaner.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.format.Formatter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private enum class ShizukuState { NOT_RUNNING, NEED_PERMISSION, DENIED_FOREVER, READY }

    private lateinit var tvPercent: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnClean: MaterialButton

    private val handler = Handler(Looper.getMainLooper())

    // Conexión con el servicio de shell que corre dentro de Shizuku
    @Volatile
    private var shellBinder: IBinder? = null
    private var bindRequested = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            shellBinder = binder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellBinder = null
            bindRequested = false
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { updateShizukuStatus() }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellBinder = null
        bindRequested = false
        runOnUiThread { updateShizukuStatus() }
    }

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
        tvShizuku = findViewById(R.id.tvShizuku)
        progress = findViewById(R.id.progress)
        btnClean = findViewById(R.id.btnClean)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        btnClean.setOnClickListener { cleanRam() }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (bindRequested) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- RAM

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

    // ------------------------------------------------------------ Shizuku

    private fun shizukuState(): ShizukuState {
        return try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED_FOREVER
                else -> ShizukuState.NEED_PERMISSION
            }
        } catch (_: Throwable) {
            ShizukuState.NOT_RUNNING
        }
    }

    private fun updateShizukuStatus() {
        tvShizuku.setText(
            when (shizukuState()) {
                ShizukuState.READY -> R.string.shizuku_ready
                ShizukuState.NEED_PERMISSION -> R.string.shizuku_need_permission
                ShizukuState.DENIED_FOREVER -> R.string.shizuku_denied
                ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
            }
        )
    }

    private fun bindShell() {
        if (bindRequested) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            bindRequested = true
        } catch (_: Throwable) {
        }
    }

    /** Espera (hasta 6 segundos) a que el servicio de shell esté conectado. */
    private fun waitForShell(): IBinder? {
        handler.post { bindShell() }
        var waited = 0
        while (shellBinder == null && waited < 6000) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
            waited += 100
        }
        return shellBinder
    }

    /** Ejecuta comandos de shell con permisos de Shizuku. Devuelve null si falló. */
    private fun runWithShizuku(script: String): String? {
        val binder = waitForShell() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(script)
            binder.transact(ShellService.CODE_RUN, data, reply, 0)
            reply.readException()
            reply.readString() ?: ""
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ----------------------------------------------------------- Limpieza

    private fun cleanRam() {
        when (shizukuState()) {
            ShizukuState.READY -> forceCleanWithShizuku()
            ShizukuState.NEED_PERMISSION -> {
                // Pide el permiso y mientras tanto hace la limpieza básica
                try {
                    Shizuku.requestPermission(REQUEST_SHIZUKU)
                } catch (_: Throwable) {
                }
                basicClean(getString(R.string.hint_authorize_shizuku))
            }
            ShizukuState.DENIED_FOREVER ->
                basicClean(getString(R.string.hint_denied_shizuku))
            ShizukuState.NOT_RUNNING ->
                basicClean(getString(R.string.hint_start_shizuku))
        }
    }

    /**
     * Apps que NO se deben cerrar: esta misma, Shizuku, el launcher (pantalla de
     * inicio), el teclado, la app de llamadas y partes básicas del sistema.
     */
    private fun protectedPackages(): Set<String> {
        val set = mutableSetOf(
            packageName,
            "moe.shizuku.privileged.api",
            "android",
            "com.android.systemui",
            "com.android.phone"
        )

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let { set.add(it) }

        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { set.add(it) }

        (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            ?.defaultDialerPackage?.let { set.add(it) }

        return set
    }

    /** Apps con ícono en el menú que están abiertas o en segundo plano. */
    private fun appsToForceStop(): List<String> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val withIcon = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        val excluded = protectedPackages()

        return pm.getInstalledApplications(0)
            .filter { it.packageName in withIcon }
            .filter { it.packageName !in excluded }
            // Las que ya están detenidas no ocupan RAM, no hace falta tocarlas
            .filter { (it.flags and ApplicationInfo.FLAG_STOPPED) == 0 }
            .map { it.packageName }
    }

    private fun forceCleanWithShizuku() {
        btnClean.isEnabled = false
        tvResult.text = getString(R.string.cleaning)

        val availableBefore = readMemory().availMem

        Thread {
            val targets = appsToForceStop()
            var output: String? = ""

            if (targets.isNotEmpty()) {
                val script = targets.joinToString("\n") { "am force-stop $it" }
                output = runWithShizuku(script)
            }

            // Esperamos un momento para que Android libere la memoria
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }

            handler.post {
                if (isFinishing || isDestroyed) return@post

                if (output == null) {
                    // Shizuku no respondió: hacemos la limpieza básica
                    btnClean.isEnabled = true
                    basicClean(getString(R.string.hint_shizuku_failed))
                    return@post
                }

                val freed = readMemory().availMem - availableBefore
                tvResult.text = when {
                    targets.isEmpty() -> getString(R.string.result_force_none)
                    freed > 0 -> getString(
                        R.string.result_force_freed, targets.size, formatSize(freed)
                    )
                    else -> getString(R.string.result_force_nothing, targets.size)
                }
                updateMemoryInfo()
                btnClean.isEnabled = true
            }
        }.start()
    }

    /** Limpieza sin Shizuku: solo procesos en segundo plano. */
    private fun basicClean(hint: String) {
        btnClean.isEnabled = false
        tvResult.text = getString(R.string.cleaning)

        val availableBefore = readMemory().availMem
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager
        val myPackage = packageName

        Thread {
            var checked = 0

            for (app in pm.getInstalledApplications(0)) {
                if (app.packageName != myPackage) {
                    am.killBackgroundProcesses(app.packageName)
                    checked++
                }
            }

            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
            }

            handler.post {
                if (isFinishing || isDestroyed) return@post

                val freed = readMemory().availMem - availableBefore
                val result = if (freed > 0) {
                    getString(R.string.result_freed, formatSize(freed), checked)
                } else {
                    getString(R.string.result_nothing, checked)
                }
                tvResult.text = result + "\n\n" + hint
                updateMemoryInfo()
                btnClean.isEnabled = true
            }
        }.start()
    }

    companion object {
        private const val REQUEST_SHIZUKU = 1001
    }
}
