package com.portaretrato.app.people

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Guarda o [FaceDatabase] em disco, na área privada do app.
 *
 * ## Escrita atômica
 *
 * Grava num arquivo temporário e só então renomeia sobre o definitivo. Um
 * porta-retrato é desligado na tomada, sem desligar o sistema — se a queda
 * pegasse uma escrita direta pela metade, o arquivo ficaria truncado e o
 * usuário perderia todos os nomes que já tinha cadastrado. Renomear é atômico
 * no mesmo sistema de arquivos: ou vale o arquivo antigo inteiro, ou o novo
 * inteiro.
 *
 * ## Onde ele NÃO vai
 *
 * Embeddings faciais são dado biométrico. Este arquivo fica em `filesDir`,
 * ilegível por outros aplicativos, nunca sai do aparelho e não entra em backup
 * (ver `android:allowBackup` e as regras de extração no manifesto). É a mesma
 * postura do [com.portaretrato.app.security.CameraGuard] aplicada ao que a
 * câmera já produziu.
 */
class FaceDatabaseStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val temp = File(context.filesDir, "$FILE_NAME.parcial")

    fun load(): FaceDatabase {
        if (!file.isFile) return FaceDatabase()
        return try {
            file.inputStream().use { FaceDatabaseCodec.read(it) } ?: run {
                // Arquivo ilegível: o app volta a funcionar do zero em vez de
                // ficar preso num estado que não consegue ler. A varredura
                // reconstrói tudo; só os nomes precisam ser digitados de novo.
                Log.w(TAG, "Banco de rostos ilegível; recomeçando vazio.")
                file.renameTo(File(file.path + ".invalido"))
                FaceDatabase()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao ler o banco de rostos", e)
            FaceDatabase()
        }
    }

    fun save(db: FaceDatabase): Boolean = try {
        temp.outputStream().use { FaceDatabaseCodec.write(db, it) }
        // `renameTo` sobre um arquivo existente é `rename(2)` no Linux, que
        // substitui atomicamente. Apagar o antigo antes abriria uma janela em
        // que nenhum dos dois existe — exatamente o instante em que a queda de
        // energia custaria os nomes já cadastrados.
        temp.renameTo(file) || (file.delete() && temp.renameTo(file))
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao gravar o banco de rostos", e)
        temp.delete()
        false
    }

    /** Apaga tudo o que o app aprendeu sobre rostos. Usado pela tela de privacidade. */
    fun erase() {
        file.delete()
        temp.delete()
    }

    fun exists(): Boolean = file.isFile

    fun sizeBytes(): Long = if (file.isFile) file.length() else 0L

    private companion object {
        const val TAG = "FaceDatabaseStore"
        const val FILE_NAME = "rostos.prfd"
    }
}
