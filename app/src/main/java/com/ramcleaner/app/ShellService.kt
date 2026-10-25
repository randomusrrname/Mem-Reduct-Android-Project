package com.ramcleaner.app

import android.os.Binder
import android.os.Parcel

/**
 * Este servicio lo ejecuta Shizuku en un proceso aparte con permisos de "shell"
 * (los mismos que tiene adb). Desde ahí se puede forzar el cierre de otras apps,
 * cosa que una app común no puede hacer.
 *
 * Recibe un texto con comandos (uno por línea), los ejecuta y devuelve lo que
 * imprimieron.
 */
class ShellService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CODE_RUN -> {
                val script = data.readString() ?: ""
                val output = runScript(script)
                reply?.writeNoException()
                reply?.writeString(output)
                return true
            }
            // Código que usa Shizuku para pedirle al servicio que termine
            CODE_DESTROY -> {
                System.exit(0)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun runScript(script: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    companion object {
        const val CODE_RUN = 1
        const val CODE_DESTROY = 16777115
    }
}
