package com.portaretrato.app.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializacao de embeddings.
 *
 * Como estava: `Person.embedding` era `List<Double>` e o Room persistia via
 * `Converters.fromDoubleList`, que joga a lista no Gson e grava JSON de texto.
 * Custos empilhados:
 *
 *  - **memoria**: 192 `java.lang.Double` boxeados + o `ArrayList` custam ~4 KB
 *    por pessoa contra 768 bytes de um `FloatArray`;
 *  - **disco**: o JSON de 192 doubles em texto passa de 3 KB por pessoa,
 *    contra 768 bytes em BLOB;
 *  - **CPU**: toda leitura do banco roda o parser reflexivo do Gson e produz
 *    192 objetos que morrem logo em seguida;
 *  - **precisao**: nada se ganha usando `double` — o modelo produz `float`, e
 *    a conversao de ida e volta so gasta espaco.
 *
 * O formato aqui e um BLOB little-endian de floats. Guarde a coluna como
 * `ByteArray` no Room (`@ColumnInfo(typeAffinity = ColumnInfo.BLOB)`) e no
 * Firestore como `Blob`.
 */
object EmbeddingCodec {

    /** Empacota um embedding em bytes little-endian. */
    fun toBytes(embedding: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in embedding) buffer.putFloat(v)
        return buffer.array()
    }

    /** Desempacota. Devolve `null` se o tamanho nao bater com o esperado. */
    fun fromBytes(bytes: ByteArray?, expectedSize: Int = RecognitionTuning.EMBEDDING_SIZE): FloatArray? {
        if (bytes == null || bytes.size != expectedSize * Float.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(expectedSize) { buffer.getFloat() }
    }

    /** Empacota varios prototipos de uma pessoa num unico BLOB. */
    fun packPrototypes(prototypes: List<FloatArray>): ByteArray {
        val dim = RecognitionTuning.EMBEDDING_SIZE
        val valid = prototypes.filter { it.size == dim }
        val buffer = ByteBuffer.allocate(valid.size * dim * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (proto in valid) for (v in proto) buffer.putFloat(v)
        return buffer.array()
    }

    /** Desempacota o BLOB gerado por [packPrototypes]. */
    fun unpackPrototypes(bytes: ByteArray?): List<FloatArray> {
        val dim = RecognitionTuning.EMBEDDING_SIZE
        val stride = dim * Float.SIZE_BYTES
        if (bytes == null || bytes.isEmpty() || bytes.size % stride != 0) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / stride) { FloatArray(dim) { buffer.getFloat() } }
    }

    /**
     * Ponte de migracao para os dados ja gravados como `List<Double>`.
     *
     * IMPORTANTE: embeddings antigos foram gerados com o alinhamento errado.
     * Eles nao sao comparaveis com os novos e nao devem ser misturados na
     * mesma galeria. Ver a secao de migracao em `docs/ANALISE-E-PLANO.md`: o
     * caminho correto e reprocessar as fotos, mantendo apenas os vinculos
     * pessoa-foto que o usuario ja confirmou.
     */
    fun fromLegacyDoubles(values: List<Double>?): FloatArray? {
        if (values == null || values.size != RecognitionTuning.EMBEDDING_SIZE) return null
        return FloatArray(values.size) { values[it].toFloat() }
    }
}
