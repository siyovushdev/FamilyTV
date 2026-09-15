package ru.familytv.voicelab.llm

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Versioned, atomic, verified first-copy deployment. Nothing is stored in shared storage. */
internal object QwenModelStore {
    @Synchronized
    fun prepare(context: Context): File {
        val manifest = context.assets.open("voice-lab/llm/model.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val name = manifest.getString("file")
        val version = manifest.getString("version")
        require(name == File(name).name && version.matches(Regex("[a-f0-9]+")))
        val directory = File(context.noBackupFilesDir, "qwen/$version")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private Qwen directory" }
        val destination = File(directory, name)
        RandomAccessFile(File(directory, "deployment.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                if (destination.isFile && destination.length() == manifest.getLong("size")) {
                    Log.i("FamilyVoiceLab", "qwenModelReused version=$version bytes=${destination.length()}")
                    return destination
                }
                val start = System.nanoTime()
                val temporary = File(directory, "$name.partial")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.assets.open("voice-lab/llm/$name").use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(temporary.length() == manifest.getLong("size") && hash == manifest.getString("sha256")) {
                        "Qwen model copy failed integrity verification"
                    }
                    Os.rename(temporary.path, destination.path)
                    Log.i("FamilyVoiceLab", "qwenModelDeployed version=$version bytes=${destination.length()} " +
                        "deploymentMs=${(System.nanoTime() - start) / 1_000_000}")
                } finally {
                    temporary.delete()
                }
            }
        }
        return destination
    }
}
