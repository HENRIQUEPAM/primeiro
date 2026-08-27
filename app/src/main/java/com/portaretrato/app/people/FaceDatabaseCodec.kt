package com.portaretrato.app.people

import com.portaretrato.app.recognition.RecognitionTuning
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Serialização do [FaceDatabase] em binário.
 *
 * ## Por que binário e não JSON
 *
 * O peso do arquivo é quase todo embedding. Um protótipo de 192 floats ocupa
 * 768 bytes aqui; em JSON, cada float vira um número em texto de ~12
 * caracteres, e o mesmo vetor passa de 2,3 KB — três vezes mais em disco, mais
 * o parser rodando a cada abertura do app. Com 20 pessoas × 8 protótipos a
 * diferença já é de ~120 KB lidos e convertidos a cada início. Num porta-retrato
 * que reinicia sozinho, isso é tempo de tela preta.
 *
 * ## Formato
 *
 * Tudo big-endian (`DataOutputStream`), strings em UTF-8 com prefixo de
 * tamanho:
 *
 * ```
 * magic  "PRFD" (4 bytes)
 * versão int
 * pessoas   int  n × { id, nome, nProtótipos int, nProtótipos × 192 floats }
 * pendentes int  n × { photoId, faceIndex, l, t, r, b, quality,
 *                      sugestão (string vazia = nenhuma), similaridade,
 *                      palpiteRecusado bool, 192 floats }
 * vínculos  int  n × { photoId, nPessoas int, nPessoas × personId }
 * varridas  int  n × photoId
 * ```
 *
 * A versão existe para uma leitura futura saber recusar um arquivo que não
 * entende, em vez de interpretar bytes errados como embeddings.
 */
object FaceDatabaseCodec {

    private const val MAGIC = "PRFD"
    private const val VERSION = 1
    private const val DIM = RecognitionTuning.EMBEDDING_SIZE

    fun write(db: FaceDatabase, out: OutputStream) {
        val data = DataOutputStream(out.buffered())

        data.writeBytes(MAGIC)
        data.writeInt(VERSION)

        val people = db.people
        data.writeInt(people.size)
        for (person in people) {
            data.writeUTF(person.id)
            data.writeUTF(person.name)
            val valid = person.prototypes.filter { it.size == DIM }
            data.writeInt(valid.size)
            for (proto in valid) for (v in proto) data.writeFloat(v)
        }

        val pending = db.pending
        data.writeInt(pending.size)
        for (face in pending) {
            data.writeUTF(face.photoId)
            data.writeInt(face.faceIndex)
            data.writeInt(face.left)
            data.writeInt(face.top)
            data.writeInt(face.right)
            data.writeInt(face.bottom)
            data.writeFloat(face.quality)
            data.writeUTF(face.suggestedPersonId.orEmpty())
            data.writeFloat(face.similarity)
            data.writeBoolean(face.suggestionRejected)
            // O embedding pendente tem tamanho garantido pelo pipeline, mas um
            // arquivo antigo poderia trazer outro: normaliza aqui.
            val embedding = if (face.embedding.size == DIM) face.embedding else FloatArray(DIM)
            for (v in embedding) data.writeFloat(v)
        }

        val links = db.allLinks.filterValues { it.isNotEmpty() }
        data.writeInt(links.size)
        for ((photoId, ids) in links) {
            data.writeUTF(photoId)
            data.writeInt(ids.size)
            for (id in ids) data.writeUTF(id)
        }

        val scanned = db.scannedPhotoIds
        data.writeInt(scanned.size)
        for (photoId in scanned) data.writeUTF(photoId)

        data.flush()
    }

    fun toBytes(db: FaceDatabase): ByteArray =
        ByteArrayOutputStream().also { write(db, it) }.toByteArray()

    /**
     * Lê o banco. Devolve `null` se o arquivo não for reconhecível ou estiver
     * truncado — o chamador então começa do zero, que é muito melhor do que
     * subir com uma galeria meio lida e passar a errar reconhecimentos sem
     * nenhum sinal visível.
     */
    fun read(input: InputStream): FaceDatabase? {
        return try {
            readOrThrow(input)
        } catch (e: EOFException) {
            // Arquivo truncado: escrita interrompida por queda de energia.
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readOrThrow(input: InputStream): FaceDatabase? {
        val data = DataInputStream(input.buffered())

        val magic = ByteArray(4).also { data.readFully(it) }.toString(Charsets.US_ASCII)
        if (magic != MAGIC) return null
        if (data.readInt() != VERSION) return null

        val people = ArrayList<Person>()
        repeat(data.readInt().requirePlausible()) {
            val id = data.readUTF()
            val name = data.readUTF()
            val prototypes = ArrayList<FloatArray>()
            repeat(data.readInt().requirePlausible()) {
                prototypes += FloatArray(DIM) { data.readFloat() }
            }
            people += Person(id, name, prototypes)
        }

        val pending = ArrayList<PendingFace>()
        repeat(data.readInt().requirePlausible()) {
            val photoId = data.readUTF()
            val faceIndex = data.readInt()
            val left = data.readInt()
            val top = data.readInt()
            val right = data.readInt()
            val bottom = data.readInt()
            val quality = data.readFloat()
            val suggestion = data.readUTF().ifEmpty { null }
            val similarity = data.readFloat()
            val rejected = data.readBoolean()
            val embedding = FloatArray(DIM) { data.readFloat() }
            pending += PendingFace(
                photoId, faceIndex, left, top, right, bottom,
                embedding, quality, suggestion, similarity, rejected,
            )
        }

        val links = LinkedHashMap<String, Set<String>>()
        repeat(data.readInt().requirePlausible()) {
            val photoId = data.readUTF()
            val ids = LinkedHashSet<String>()
            repeat(data.readInt().requirePlausible()) { ids += data.readUTF() }
            links[photoId] = ids
        }

        val scanned = LinkedHashSet<String>()
        repeat(data.readInt().requirePlausible()) { scanned += data.readUTF() }

        return FaceDatabase(people, pending, links, scanned)
    }

    fun fromBytes(bytes: ByteArray): FaceDatabase? = read(bytes.inputStream())

    /**
     * Um contador corrompido (lixo lido como int) tentaria alocar bilhões de
     * elementos e derrubaria o app com OOM antes de qualquer validação. O teto
     * é folgado o bastante para qualquer acervo doméstico.
     */
    private fun Int.requirePlausible(): Int {
        require(this in 0..MAX_ENTRIES) { "contagem implausível: $this" }
        return this
    }

    private const val MAX_ENTRIES = 100_000
}
