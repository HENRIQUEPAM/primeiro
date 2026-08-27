package com.portaretrato.app.photo

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Uma foto do acervo. */
data class Photo(val id: String, val file: File, val addedAt: Long)

/**
 * Acervo de fotos do porta-retrato.
 *
 * ## Por que as fotos sao COPIADAS para dentro do app
 *
 * O seletor de fotos do Android da uma URI com permissao temporaria, que morre
 * quando o processo morre. Um porta-retrato fica ligado por meses e reinicia
 * sozinho — guardar a URI faria o acervo sumir depois do primeiro reboot.
 * Copiar para `filesDir` resolve isso e, de quebra, o acervo passa a viver na
 * area privada do app, ilegivel por outros aplicativos.
 *
 * ## Por que isto NAO pede permissao de armazenamento
 *
 * O seletor moderno (`PickMultipleVisualMedia`) roda fora do processo do app: o
 * usuario escolhe as fotos numa tela do sistema e so o que ele escolheu chega
 * aqui. Nao ha `READ_MEDIA_IMAGES`, nao ha `READ_EXTERNAL_STORAGE`, e o app
 * nunca ve o resto da galeria. E a mesma logica do CameraGuard aplicada ao
 * armazenamento: o app so alcanca o que o usuario entregou explicitamente.
 *
 * O identificador e o SHA-256 do conteudo, entao a mesma foto adicionada duas
 * vezes ocupa espaco uma vez so — comum quando a familia manda a mesma imagem
 * por caminhos diferentes.
 */
class PhotoLibrary(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    /** Fotos do acervo, da mais recente para a mais antiga. */
    fun all(): List<Photo> = directory
        .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
        .orEmpty()
        .map { Photo(it.nameWithoutExtension, it, it.lastModified()) }
        .sortedByDescending { it.addedAt }

    fun ids(): List<String> = all().map { it.id }

    fun fileFor(id: String): File? =
        File(directory, "$id$EXTENSION").takeIf { it.isFile }

    fun count(): Int = directory.listFiles()?.count { it.isFile } ?: 0

    /**
     * Importa fotos escolhidas no seletor do sistema.
     *
     * @return quantas foram efetivamente adicionadas (duplicatas nao contam).
     */
    fun import(uris: List<Uri>): Int {
        var added = 0
        for (uri in uris) {
            if (importOne(uri)) added++
        }
        return added
    }

    private fun importOne(uri: Uri): Boolean = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) {
            false
        } else {
            val id = sha256(bytes)
            val target = File(directory, "$id$EXTENSION")
            if (target.exists()) {
                // Mesma foto ja no acervo: nao duplica o arquivo.
                false
            } else {
                // Grava num temporario e renomeia: se o processo morrer no meio
                // da copia, o acervo nao fica com um arquivo pela metade que o
                // decodificador tentaria abrir depois.
                val temp = File(directory, "$id$TEMP_EXTENSION")
                temp.writeBytes(bytes)
                temp.renameTo(target)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao importar $uri", e)
        false
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Foto grande demais para importar: $uri", e)
        false
    }

    fun delete(id: String): Boolean = fileFor(id)?.delete() ?: false

    /** Remove restos de importacoes interrompidas. Chame na abertura do app. */
    fun cleanupTemporaries() {
        directory.listFiles { f -> f.name.endsWith(TEMP_EXTENSION) }
            ?.forEach { it.delete() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(ID_LENGTH)

    private companion object {
        const val TAG = "PhotoLibrary"
        const val DIRECTORY = "fotos"
        const val EXTENSION = ".jpg"
        const val TEMP_EXTENSION = ".parcial"

        /**
         * 32 caracteres hex = 128 bits do SHA-256. Muito alem do necessario
         * para evitar colisao num acervo domestico, e mantem o nome do arquivo
         * legivel.
         */
        const val ID_LENGTH = 32
    }
}
