package ru.familytv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import org.json.JSONObject
import java.io.File

object ProgramLoader {

    private const val ROOT_FOLDER = "FamilyTV"
    private const val MEDIA_FOLDER = "media"
    private const val PROGRAM_FILE = "program.json"

    fun getRootDirectory(context: Context): File {
        val externalDir = requireNotNull(
            context.getExternalFilesDir(null)
        ) {
            "External storage is unavailable"
        }

        return File(externalDir, ROOT_FOLDER)
    }

    fun prepareDirectories(context: Context): File {
        val root = getRootDirectory(context)
        val media = File(root, MEDIA_FOLDER)

        if (!root.exists()) {
            root.mkdirs()
        }

        if (!media.exists()) {
            media.mkdirs()
        }

        return root
    }

    fun load(context: Context): LoadedProgram {
        val root = prepareDirectories(context)

        val programFile = File(
            root,
            PROGRAM_FILE
        )

        if (!programFile.exists()) {
            throw IllegalStateException(
                """
                program.json не найден.

                Ожидаемый путь:
                ${programFile.absolutePath}
                """.trimIndent()
            )
        }

        val json = JSONObject(
            programFile.readText()
        )

        val programId = json.getString("id")

        val jsonItems = json.getJSONArray("items")

        if (jsonItems.length() == 0) {
            throw IllegalStateException(
                "В program.json нет элементов"
            )
        }

        val mediaDirectory = File(
            root,
            MEDIA_FOLDER
        )

        val mediaItems = mutableListOf<MediaItem>()

        for (index in 0 until jsonItems.length()) {

            val fileName = jsonItems.getString(index)

            val file = File(
                mediaDirectory,
                fileName
            )

            if (!file.exists()) {
                throw IllegalStateException(
                    """
                    Видео не найдено:
                    $fileName

                    Ожидаемый путь:
                    ${file.absolutePath}
                    """.trimIndent()
                )
            }

            if (!file.isFile) {
                throw IllegalStateException(
                    "Это не файл: ${file.absolutePath}"
                )
            }

            mediaItems += MediaItem.fromUri(
                Uri.fromFile(file)
            )
        }

        return LoadedProgram(
            config = ProgramConfig(
                id = programId,
                items = List(jsonItems.length()) { index ->
                    jsonItems.getString(index)
                }
            ),
            mediaItems = mediaItems
        )
    }
}

data class LoadedProgram(
    val config: ProgramConfig,
    val mediaItems: List<MediaItem>
)