package com.portaretrato.app.people

import com.portaretrato.app.recognition.FaceGallery
import com.portaretrato.app.recognition.PersonPrototypes
import com.portaretrato.app.recognition.RecognitionDecision
import com.portaretrato.app.recognition.RecognitionMatcher
import com.portaretrato.app.recognition.ScannedPhoto

/** Uma pessoa reconhecível. */
class Person(
    val id: String,
    val name: String,
    /** Até [com.portaretrato.app.recognition.RecognitionTuning.MAX_PROTOTYPES_PER_PERSON] vetores. */
    val prototypes: List<FloatArray>,
    /**
     * Telefone em E.164 sem "+", quando o usuário vinculou um contato ao
     * nomear o rosto. `null` até lá — reconhecer um rosto não pressupõe saber
     * o telefone de quem ele é.
     */
    val phone: String? = null,
) {
    fun withName(newName: String) = Person(id, newName, prototypes, phone)
    fun withPrototypes(newPrototypes: List<FloatArray>) = Person(id, name, newPrototypes, phone)
    fun withPhone(newPhone: String?) = Person(id, name, prototypes, newPhone)
}

/**
 * Rosto detectado que ainda não tem dono.
 *
 * Guarda a caixa em coordenadas da foto para o recorte ser feito na hora de
 * exibir. Armazenar uma miniatura por rosto duplicaria o acervo em disco sem
 * necessidade — a foto original já está ali.
 */
class PendingFace(
    val photoId: String,
    val faceIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val embedding: FloatArray,
    val quality: Float,
    /** Palpite do matcher, quando houve um. */
    val suggestedPersonId: String?,
    val similarity: Float,
    /**
     * O usuário já disse que um palpite estava errado para este rosto.
     *
     * Sem este campo não dá para separar "nunca teve palpite" de "teve e foi
     * recusado" — os dois têm `suggestedPersonId == null`. A diferença importa:
     * o primeiro deve receber um palpite quando uma pessoa nova é cadastrada, e
     * o segundo nunca mais deve ser incomodado com adivinhação.
     */
    val suggestionRejected: Boolean = false,
) {
    val id: String get() = "$photoId#$faceIndex"

    fun withoutSuggestion() = PendingFace(
        photoId, faceIndex, left, top, right, bottom, embedding, quality, null, 0f,
        suggestionRejected = true,
    )

    fun withSuggestion(personId: String?, sim: Float) = PendingFace(
        photoId, faceIndex, left, top, right, bottom, embedding, quality, personId, sim,
        suggestionRejected = suggestionRejected,
    )
}

/** O que uma foto rendeu. */
data class ScanSummary(
    val autoLinked: Int = 0,
    val suggested: Int = 0,
    val unknown: Int = 0,
    val rejected: Int = 0,
) {
    operator fun plus(other: ScanSummary) = ScanSummary(
        autoLinked + other.autoLinked,
        suggested + other.suggested,
        unknown + other.unknown,
        rejected + other.rejected,
    )
}

/**
 * O índice de pessoas do acervo: quem existe, quem está em cada foto, e quais
 * rostos ainda esperam um nome.
 *
 * Lógica pura, sem Android e sem I/O — a persistência é do [FaceDatabaseStore],
 * a varredura é do [com.portaretrato.app.recognition.FaceScanCoordinator]. Isso
 * é o que permite testar na JVM as regras que realmente decidem se o
 * reconhecimento acerta ou erra.
 *
 * ## O que aqui não é óbvio
 *
 * - **Nomear um rosto reprocessa a fila inteira.** Este é o ganho central: a
 *   primeira foto da avó chega como desconhecida, o usuário digita "Vó Maria"
 *   uma vez, e as outras 40 fotos dela que estavam na fila são resolvidas na
 *   mesma ação. Sem isso, o cadastro seria linear no número de fotos e ninguém
 *   terminaria.
 * - **A galeria é cacheada e invalidada em cada mutação.** Reconstruir por foto
 *   desperdiça; reconstruir nunca devolve resultado errado depois do primeiro
 *   protótipo novo.
 * - **A fila de revisão tem teto.** Um acervo de 3.000 fotos pode gerar
 *   milhares de rostos sem dono, e uma fila infinita é uma fila que ninguém
 *   abre. Cheia, ela passa a **substituir o pior rosto guardado** quando chega
 *   um melhor — o usuário revisa os rostos mais nítidos, que são justamente os
 *   que ele consegue identificar.
 * - **Nome repetido não é sempre a mesma pessoa.** Duas "Marias" na família são
 *   comuns. O embedding decide entre juntar e criar homônimo.
 *
 * Não é thread-safe.
 */
class FaceDatabase(
    people: List<Person> = emptyList(),
    pending: List<PendingFace> = emptyList(),
    links: Map<String, Set<String>> = emptyMap(),
    scanned: Set<String> = emptySet(),
) {

    private val peopleById = LinkedHashMap<String, Person>()
    private val pendingById = LinkedHashMap<String, PendingFace>()
    private val linksByPhoto = LinkedHashMap<String, MutableSet<String>>()
    private val scannedPhotos = LinkedHashSet<String>()

    private var cachedGallery: FaceGallery? = null
    private var nextPersonNumber = 1

    init {
        people.forEach { peopleById[it.id] = it }
        pending.forEach { pendingById[it.id] = it }
        links.forEach { (photo, ids) -> if (ids.isNotEmpty()) linksByPhoto[photo] = ids.toMutableSet() }
        scannedPhotos += scanned
        // Sequência dos ids gerados: retoma acima do maior já existente para um
        // id nunca colidir com outro depois de recarregar do disco.
        nextPersonNumber = 1 + (
            peopleById.keys.mapNotNull { it.removePrefix(ID_PREFIX).toIntOrNull() }.maxOrNull() ?: 0
            )
    }

    // ------------------------------------------------------------- consultas

    val people: List<Person> get() = peopleById.values.toList()
    val pending: List<PendingFace> get() = pendingById.values.toList()
    val scannedPhotoIds: Set<String> get() = scannedPhotos
    val pendingCount: Int get() = pendingById.size
    val personCount: Int get() = peopleById.size

    fun person(id: String): Person? = peopleById[id]

    fun isScanned(photoId: String): Boolean = photoId in scannedPhotos

    /** Ids das pessoas presentes numa foto. */
    fun personIdsIn(photoId: String): Set<String> = linksByPhoto[photoId].orEmpty()

    /**
     * Todos os vínculos foto→pessoas. Existe para o codec gravar a fonte da
     * verdade em vez de reconstruí-la a partir da lista de fotos varridas —
     * derivar um dado de outro no momento de salvar é como se perde vínculo.
     */
    val allLinks: Map<String, Set<String>>
        get() = linksByPhoto.mapValues { it.value.toSet() }

    /** Nomes de quem está na foto, na ordem em que as pessoas foram criadas. */
    fun namesIn(photoId: String): List<String> = peopleIn(photoId).map { it.name }

    /**
     * Pessoas reconhecidas na foto, com telefone incluído quando houver.
     *
     * Existe separada de [namesIn] porque quem chama do porta-retrato (ver
     * [com.portaretrato.app.ui.SlideshowActivity]) precisa do telefone, não só
     * do nome, para saber quem da foto pode ser chamado.
     */
    fun peopleIn(photoId: String): List<Person> {
        val ids = linksByPhoto[photoId] ?: return emptyList()
        return peopleById.values.filter { it.id in ids }
    }

    fun photosOf(personId: String): List<String> =
        linksByPhoto.entries.filter { personId in it.value }.map { it.key }

    /** Índice para consulta. Reconstruído só depois de uma mudança real. */
    fun gallery(): FaceGallery {
        cachedGallery?.let { return it }
        val built = FaceGallery.build(
            peopleById.values
                .filter { it.prototypes.isNotEmpty() }
                .map { PersonPrototypes(it.id, it.name, it.prototypes) },
        )
        cachedGallery = built
        return built
    }

    // -------------------------------------------------------------- mutações

    /**
     * Registra o resultado de uma foto.
     *
     * Idempotente: chamar duas vezes para a mesma foto não duplica vínculo nem
     * enche a fila — o que acontece de verdade quando a varredura é
     * interrompida e retomada.
     */
    fun applyScan(scan: ScannedPhoto): ScanSummary {
        // Uma foto reprocessada substitui o que sabíamos dela: sem isso, apagar
        // e re-adicionar a mesma imagem acumularia rostos fantasmas.
        pendingById.keys.removeAll { it.startsWith("${scan.photoId}#") }

        var summary = ScanSummary()

        scan.faces.forEachIndexed { index, face ->
            when (val decision = face.decision) {
                is RecognitionDecision.AutoLink -> {
                    link(scan.photoId, decision.personId)
                    reinforce(decision.personId, face.embedding)
                    summary = summary + ScanSummary(autoLinked = 1)
                }

                is RecognitionDecision.Suggest -> {
                    offer(
                        PendingFace(
                            photoId = scan.photoId,
                            faceIndex = index,
                            left = face.boundingBox.left,
                            top = face.boundingBox.top,
                            right = face.boundingBox.right,
                            bottom = face.boundingBox.bottom,
                            embedding = face.embedding,
                            quality = face.quality.score,
                            suggestedPersonId = decision.personId,
                            similarity = decision.similarity,
                        ),
                    )
                    summary = summary + ScanSummary(suggested = 1)
                }

                RecognitionDecision.Unknown -> {
                    offer(
                        PendingFace(
                            photoId = scan.photoId,
                            faceIndex = index,
                            left = face.boundingBox.left,
                            top = face.boundingBox.top,
                            right = face.boundingBox.right,
                            bottom = face.boundingBox.bottom,
                            embedding = face.embedding,
                            quality = face.quality.score,
                            suggestedPersonId = null,
                            similarity = 0f,
                        ),
                    )
                    summary = summary + ScanSummary(unknown = 1)
                }

                is RecognitionDecision.Rejected -> summary = summary + ScanSummary(rejected = 1)
            }
        }

        summary = summary + ScanSummary(rejected = scan.rejectedCount)
        scannedPhotos += scan.photoId
        return summary
    }

    /**
     * O usuário deu um nome a um rosto da fila.
     *
     * @return quantos OUTROS rostos da fila foram resolvidos junto, por
     *   passarem a bater com a pessoa recém-criada. É o número que a tela
     *   mostra ("mais 12 fotos reconhecidas").
     */
    fun nameFace(pendingId: String, rawName: String): NameResult {
        val face = pendingById[pendingId] ?: return NameResult(null, 0)
        val name = rawName.trim()
        if (name.isEmpty()) return NameResult(null, 0)

        val person = resolvePersonForName(name, face.embedding)
        pendingById.remove(pendingId)
        link(face.photoId, person.id)
        reinforce(person.id, face.embedding)

        return NameResult(peopleById[person.id], reclassifyPending())
    }

    /** O usuário confirmou o palpite do app. */
    fun confirmSuggestion(pendingId: String): NameResult {
        val face = pendingById[pendingId] ?: return NameResult(null, 0)
        val personId = face.suggestedPersonId ?: return NameResult(null, 0)
        if (personId !in peopleById) return NameResult(null, 0)

        pendingById.remove(pendingId)
        link(face.photoId, personId)
        reinforce(personId, face.embedding)
        return NameResult(peopleById[personId], reclassifyPending())
    }

    /**
     * O usuário reconheceu o rosto como sendo uma pessoa que **já existe**,
     * escolhida de uma lista — ignorando o que a semelhança calculou.
     *
     * Existe para o caso em que o humano acerta e o algoritmo não: ângulo
     * ruim, óculos novos, criança que mudou. Sem isto, o único jeito de
     * vincular a uma pessoa existente era digitar o nome dela de novo — e se a
     * semelhança do rosto ficasse abaixo de
     * [com.portaretrato.app.recognition.RecognitionTuning.SAME_NAME_CONFIRM_THRESHOLD],
     * [nameFace] criaria um homônimo em vez de juntar, mesmo com o nome
     * idêntico. Aqui o vínculo é sempre com o `personId` informado, sem
     * segunda-adivinhação.
     */
    fun assignToExistingPerson(pendingId: String, personId: String): NameResult {
        val face = pendingById[pendingId] ?: return NameResult(null, 0)
        if (personId !in peopleById) return NameResult(null, 0)

        pendingById.remove(pendingId)
        link(face.photoId, personId)
        reinforce(personId, face.embedding)
        return NameResult(peopleById[personId], reclassifyPending())
    }

    /**
     * O palpite estava errado. O rosto continua na fila, mas sem sugestão —
     * repetir o mesmo palpite recusado é a forma mais rápida de o usuário
     * perder a confiança no app.
     */
    fun rejectSuggestion(pendingId: String) {
        val face = pendingById[pendingId] ?: return
        pendingById[pendingId] = face.withoutSuggestion()
    }

    /** O usuário não quer catalogar este rosto (um estranho ao fundo). */
    fun dismiss(pendingId: String) {
        pendingById.remove(pendingId)
    }

    fun renamePerson(personId: String, newName: String): Boolean {
        val person = peopleById[personId] ?: return false
        val name = newName.trim()
        if (name.isEmpty()) return false
        peopleById[personId] = person.withName(name)
        invalidate()
        return true
    }

    /**
     * Vincula (ou desvincula, com `phone = null`) um telefone à pessoa.
     *
     * Não mexe na galeria — telefone não entra no reconhecimento, só no
     * cadastro. Quem decide se isso também cadastra a pessoa para chamar é o
     * chamador (ver [com.portaretrato.app.ui.PeopleActivity]); esta classe
     * conhece rostos, não sabe o que é uma chamada.
     */
    fun linkPhone(personId: String, phone: String?): Boolean {
        val person = peopleById[personId] ?: return false
        peopleById[personId] = person.withPhone(phone)
        return true
    }

    /** Apaga uma pessoa e todos os seus vínculos. */
    fun removePerson(personId: String): Boolean {
        if (peopleById.remove(personId) == null) return false
        linksByPhoto.values.forEach { it.remove(personId) }
        linksByPhoto.entries.removeAll { it.value.isEmpty() }
        pendingById.entries.removeAll { it.value.suggestedPersonId == personId }
        invalidate()
        return true
    }

    /**
     * Descarta tudo o que se refere a fotos que não existem mais.
     *
     * Chamado depois de listar o acervo: fotos apagadas deixariam vínculos
     * apontando para arquivos ausentes e rostos na fila que não têm como ser
     * exibidos.
     */
    fun retainOnly(existingPhotoIds: Set<String>) {
        scannedPhotos.retainAll(existingPhotoIds)
        linksByPhoto.keys.retainAll(existingPhotoIds)
        pendingById.entries.removeAll { it.value.photoId !in existingPhotoIds }
    }

    // ------------------------------------------------------------- internos

    private fun link(photoId: String, personId: String) {
        linksByPhoto.getOrPut(photoId) { LinkedHashSet() }.add(personId)
    }

    /** Acrescenta o vetor aos protótipos da pessoa, se ele agregar algo. */
    private fun reinforce(personId: String, embedding: FloatArray) {
        val person = peopleById[personId] ?: return
        val merged = FaceGallery.mergePrototype(person.prototypes, embedding)
        if (merged !== person.prototypes) {
            peopleById[personId] = person.withPrototypes(merged)
            invalidate()
        }
    }

    /**
     * Insere na fila respeitando o teto.
     *
     * Cheia, entra apenas quem for mais nítido que o pior guardado. Um rosto
     * borrado a mais na fila não ajuda ninguém; um nítido a mais, sim.
     */
    private fun offer(face: PendingFace) {
        if (pendingById.size < MAX_PENDING) {
            pendingById[face.id] = face
            return
        }
        val worst = pendingById.values.minByOrNull { it.quality } ?: return
        if (face.quality > worst.quality) {
            pendingById.remove(worst.id)
            pendingById[face.id] = face
        }
    }

    /**
     * Decide a qual pessoa um nome digitado corresponde.
     *
     * Nome igual E rosto parecido => é a mesma pessoa, junta.
     * Nome igual e rosto diferente => homônimo, cria "Maria (2)".
     */
    private fun resolvePersonForName(name: String, embedding: FloatArray): Person {
        val sameName = peopleById.values.filter { it.name.equalsIgnoreCaseAndAccentless(name) }

        sameName.firstOrNull { RecognitionMatcher.isLikelySamePerson(embedding, it.prototypes) }
            ?.let { return it }

        val finalName = if (sameName.isEmpty()) name else "$name (${sameName.size + 1})"
        val person = Person(ID_PREFIX + nextPersonNumber++, finalName, listOf(embedding))
        peopleById[person.id] = person
        invalidate()
        return person
    }

    /**
     * Reavalia a fila contra a galeria atualizada.
     *
     * @return quantos rostos saíram da fila por vínculo automático.
     */
    private fun reclassifyPending(): Int {
        if (pendingById.isEmpty()) return 0
        val gallery = gallery()
        var resolved = 0

        for (face in pendingById.values.toList()) {
            val match = gallery.match(face.embedding) ?: continue

            // O limiar aqui é o de vínculo automático, com a mesma exigência de
            // margem: o usuário acabou de dizer quem é uma pessoa, não deu
            // permissão para o app adivinhar as demais com menos rigor.
            val autoThreshold = com.portaretrato.app.recognition.RecognitionTuning.AUTO_LINK_THRESHOLD +
                (1f - face.quality) * 0.05f

            when {
                match.similarity >= autoThreshold &&
                    match.margin >= com.portaretrato.app.recognition.RecognitionTuning.AUTO_LINK_MARGIN -> {
                    pendingById.remove(face.id)
                    link(face.photoId, match.personId)
                    reinforce(match.personId, face.embedding)
                    resolved++
                }

                match.similarity >= com.portaretrato.app.recognition.RecognitionTuning.SUGGEST_THRESHOLD ->
                    // Um rosto cujo palpite o usuário já recusou não volta a ser
                    // palpitado: insistir é a forma mais rápida de ele parar de
                    // confiar no que a tela pergunta.
                    if (!face.suggestionRejected) {
                        pendingById[face.id] = face.withSuggestion(match.personId, match.similarity)
                    }

                else -> Unit
            }
        }
        return resolved
    }

    private fun invalidate() {
        cachedGallery = null
    }

    /** Resultado de nomear ou confirmar um rosto. */
    data class NameResult(val person: Person?, val alsoResolved: Int)

    private companion object {
        const val ID_PREFIX = "p"

        /**
         * Teto da fila de revisão. 300 rostos já são mais do que um idoso vai
         * revisar de uma vez; acima disso a fila só cresce sem ser usada.
         */
        const val MAX_PENDING = 300
    }
}

/**
 * Comparação de nomes tolerante a acento e caixa: "Vó Maria" e "vo maria"
 * digitados em dias diferentes têm de cair na mesma pessoa. Sem isso, o acervo
 * enche de duplicatas que o usuário não entende por que existem.
 */
internal fun String.equalsIgnoreCaseAndAccentless(other: String): Boolean =
    foldAccents() == other.foldAccents()

private fun String.foldAccents(): String {
    val from = "áàâãäéèêëíìîïóòôõöúùûüçñ"
    val to = "aaaaaeeeeiiiiooooouuuucn"
    val builder = StringBuilder(length)
    for (ch in lowercase().trim()) {
        val index = from.indexOf(ch)
        builder.append(if (index >= 0) to[index] else ch)
    }
    return builder.toString().replace(Regex("\\s+"), " ")
}
