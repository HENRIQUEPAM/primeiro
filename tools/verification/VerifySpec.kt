import com.portaretrato.app.call.CallEndReason
import com.portaretrato.app.call.PairingProtocol
import com.portaretrato.app.call.CallMethod
import com.portaretrato.app.call.CallOptions
import com.portaretrato.app.call.SdpSigner
import com.portaretrato.app.call.UnavailableReason
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"))
}.generateKeyPair()

// ---------------------------------------------------------------------------
// 1. Assinatura de SDP (ECDSA P-256) — Secao 7.6
// ---------------------------------------------------------------------------
fun testSigning() {
    println("[1] Assinatura de SDP com ECDSA P-256")

    val device = p256()
    val impostor = p256()
    val sdp = "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"

    val signature = SdpSigner.sign(sdp, device.private)
    check("assinatura gerada", signature.isNotBlank())
    check("verifica com a chave certa", SdpSigner.verify(sdp, signature, device.public))

    // O ataque que a assinatura existe para bloquear: outro aparelho grava um
    // SDP na sinalizacao se passando pelo par legitimo.
    check(
        "REJEITA assinatura de outra chave",
        !SdpSigner.verify(sdp, signature, impostor.public),
    )

    // SDP adulterado depois de assinado (troca do candidato/IP).
    val tampered = sdp.replace("127.0.0.1", "10.0.0.9")
    check("REJEITA SDP adulterado", !SdpSigner.verify(tampered, signature, device.public))

    // Robustez: nada aqui pode lancar, senao vira crash ao receber chamada.
    check("Base64 invalido devolve false", !SdpSigner.verify(sdp, "!!!nao-e-base64!!!", device.public))
    check("assinatura vazia devolve false", !SdpSigner.verify(sdp, "", device.public))
    check("assinatura truncada devolve false", !SdpSigner.verify(sdp, signature.take(10), device.public))
    check(
        "bytes aleatorios devolvem false",
        !SdpSigner.verify(sdp, java.util.Base64.getEncoder().encodeToString(ByteArray(70) { 7 }), device.public),
    )

    // ECDSA e aleatorizado: duas assinaturas diferem, ambas validam.
    val again = SdpSigner.sign(sdp, device.private)
    check("ECDSA produz assinaturas distintas", again != signature)
    check("a segunda tambem valida", SdpSigner.verify(sdp, again, device.public))

    val encoded = SdpSigner.encodePublicKey(device.public)
    check("chave publica codificada em Base64 X.509", encoded.isNotBlank() && encoded.length > 100)
}

// ---------------------------------------------------------------------------
// 2. Numero de seguranca do pareamento — Secao 7.6
// ---------------------------------------------------------------------------
fun testSafetyNumber() {
    println("[2] Numero de seguranca do pareamento")

    val a = p256()
    val b = p256()
    val c = p256()

    val fromA = SdpSigner.safetyNumber(a.public, b.public)
    val fromB = SdpSigner.safetyNumber(b.public, a.public)

    check("SIMETRICO: os dois aparelhos veem o mesmo numero", fromA == fromB, "$fromA vs $fromB")
    check("numero diferente para outro par", SdpSigner.safetyNumber(a.public, c.public) != fromA)

    val digits = fromA.filter(Char::isDigit)
    check("tem 30 digitos", digits.length == SdpSigner.SAFETY_NUMBER_DIGITS, "=${digits.length}")
    check("so digitos e espacos", fromA.all { it.isDigit() || it == ' ' }, "=$fromA")
    check("agrupado em 6 grupos de 5", fromA.split(" ").let { g -> g.size == 6 && g.all { it.length == 5 } }, "=$fromA")

    // Estabilidade: o mesmo par sempre da o mesmo numero.
    check("estavel entre chamadas", SdpSigner.safetyNumber(a.public, b.public) == fromA)

    println("      exemplo: $fromA")
}

// ---------------------------------------------------------------------------
// 3. Frescor do convite — protecao anti "chamada fantasma", Secao 7.4
// ---------------------------------------------------------------------------
fun testFreshness() {
    println("[3] Frescor do convite (anti chamada fantasma)")

    val now = 1_700_000_000_000L

    check("convite de agora e fresco", PairingProtocol.isFresh(now, now))
    check("convite de 10s atras e fresco", PairingProtocol.isFresh(now - 10_000, now))
    check("convite de 19s atras e fresco", PairingProtocol.isFresh(now - 19_000, now))
    check("convite de 25s atras NAO e fresco", !PairingProtocol.isFresh(now - 25_000, now))
    check("convite de 5min atras NAO e fresco", !PairingProtocol.isFresh(now - 300_000, now))

    // O caso real: app acordado com atraso pela otimizacao de bateria do MIUI
    // e tocando por uma chamada que ja acabou.
    check("app acordado 60s depois nao toca", !PairingProtocol.isFresh(now - 60_000, now))

    check("createdAt nulo NAO e fresco", !PairingProtocol.isFresh(null, now))
    check("createdAt zero NAO e fresco", !PairingProtocol.isFresh(0L, now))

    // Tolerancia pequena para desvio de relogio do servidor.
    check("1s no futuro ainda e aceito", PairingProtocol.isFresh(now + 1_000, now))
    check("10s no futuro e rejeitado", !PairingProtocol.isFresh(now + 10_000, now))
}

// ---------------------------------------------------------------------------
// 4. Schema canonico — Secao 9
// ---------------------------------------------------------------------------
fun testSchema() {
    println("[4] Schema canonico de sinalizacao")

    check(
        "caminho e /pairings/{id}/callSessions",
        PairingProtocol.callSessionsPath("par123") == "pairings/par123/callSessions",
        "=${PairingProtocol.callSessionsPath("par123")}",
    )

    val offer = PairingProtocol.offerDocument(
        callerDeviceId = "devA",
        calleeDeviceId = "devB",
        ownerUid = "uid-1",
        sdp = "v=0...",
        signatureBase64 = "AAAA",
        video = true,
    )
    check("offer traz ownerUid denormalizado", offer[PairingProtocol.FIELD_OWNER_UID] == "uid-1")
    check("offer nasce em RINGING", offer[PairingProtocol.FIELD_STATE] == PairingProtocol.SESSION_RINGING)
    check("offer carrega a assinatura", offer[PairingProtocol.FIELD_OFFER_SIGNATURE] == "AAAA")
    check(
        "offer NAO grava createdAt local (usa serverTimestamp)",
        !offer.containsKey(PairingProtocol.FIELD_CREATED_AT),
    )

    val answer = PairingProtocol.answerDocument("v=0-answer", "BBBB")
    check("answer muda o estado para ANSWERED", answer[PairingProtocol.FIELD_STATE] == PairingProtocol.SESSION_ANSWERED)
    check(
        "answer NAO reescreve campos da offer",
        answer.keys.none { it in setOf(PairingProtocol.FIELD_OFFER_SDP, PairingProtocol.FIELD_CALLER_DEVICE_ID, PairingProtocol.FIELD_OWNER_UID) },
    )

    val ended = PairingProtocol.endDocument(CallEndReason.REMOTE_HANGUP)
    check("encerramento registra o motivo", ended[PairingProtocol.FIELD_END_REASON] == "REMOTE_HANGUP")

    check("chave de identidade unica P-256", PairingProtocol.FIELD_PUBLIC_KEY_P256 == "publicKeyP256")
    check("TTL do pairingRequest e 2 min", PairingProtocol.PAIRING_REQUEST_TTL_MS == 120_000L)
    check("timeout do cliente vem ANTES do servidor",
        PairingProtocol.CLIENT_RING_TIMEOUT_MS < PairingProtocol.RING_TIMEOUT_MS)
}

// ---------------------------------------------------------------------------
// 5. Normalizacao de telefone — correcao sobre a v2.9
// ---------------------------------------------------------------------------
fun testPhoneNormalization() {
    println("[5] Normalizacao de telefone para o WhatsApp")

    // Este e exatamente o bug do app atual: so filtrava digitos, sem DDI.
    check("celular com DDD ganha o DDI", norm("11 99999-9999") == "5511999999999", "=${norm("11 99999-9999")}")
    check("fixo com DDD ganha o DDI", norm("(11) 3333-4444") == "551133334444", "=${norm("(11) 3333-4444")}")
    check("numero ja com +55 e mantido", norm("+55 11 99999-9999") == "5511999999999")
    check("numero ja com 55 sem + e mantido", norm("5511999999999") == "5511999999999")
    // O "+" e o unico sinal confiavel de que o numero ja esta completo.
    check("numero internacional com + e mantido", norm("+1 415 555 2671") == "14155552671", "=${norm("+1 415 555 2671")}")
    check("prefixo 00 tambem e internacional", norm("001 415 555 2671") == "14155552671", "=${norm("001 415 555 2671")}")
    check("+55 explicito nao duplica o DDI", norm("+5511999999999") == "5511999999999")
    // Sem "+", 11 digitos e assumido brasileiro — decisao de produto explicita.
    check("sem + , 11 digitos assume Brasil", norm("11999999999") == "5511999999999")
    check("sem DDD devolve null (nao da para adivinhar)", norm("99999-9999") == null)
    check("vazio devolve null", norm("") == null)
    check("so pontuacao devolve null", norm("()- ") == null)
}

fun norm(p: String): String? = com.portaretrato.app.call.PhoneNumbers.normalize(p)

// ---------------------------------------------------------------------------
// 6. Opcoes de chamada por contato
// ---------------------------------------------------------------------------
fun options(
    phone: String? = "11999999999",
    configured: Boolean = true,
    paired: String? = "dev-b",
    online: Boolean = true,
    inCall: Boolean = false,
) = CallOptions.forContact(phone, configured, paired, online, inCall)

fun opt(list: List<com.portaretrato.app.call.CallOption>, m: CallMethod) = list.first { it.method == m }

fun testCallOptions() {
    println("[6] Opcoes de chamada por contato")

    // Situacao REAL de hoje: sem Firebase configurado.
    val hoje = options(configured = false)
    check(
        "sem configuracao, chamada pelo app fica indisponivel",
        !opt(hoje, CallMethod.APP_VIDEO).available,
    )
    check(
        "e explica o motivo em portugues",
        opt(hoje, CallMethod.APP_VIDEO).explanation == "Chamada pelo app ainda nao configurada".replace("nao", "não"),
        "=${opt(hoje, CallMethod.APP_VIDEO).explanation}",
    )
    // O ponto central: mesmo assim o usuario CONSEGUE falar com a pessoa.
    check("WhatsApp video continua disponivel", opt(hoje, CallMethod.WHATSAPP_VIDEO).available)
    check("WhatsApp chat continua disponivel", opt(hoje, CallMethod.WHATSAPP_CHAT).available)
    check("telefone continua disponivel", opt(hoje, CallMethod.PHONE_DIAL).available)
    check("ha ao menos uma forma de falar", CallOptions.hasAnyOption(hoje))
    check(
        "o toque simples cai no WhatsApp video",
        CallOptions.best(hoje)?.method == CallMethod.WHATSAPP_VIDEO,
        "=${CallOptions.best(hoje)?.method}",
    )

    // Tudo configurado: o app assume a preferencia.
    val completo = options()
    check("com tudo pronto, chamada pelo app fica disponivel", opt(completo, CallMethod.APP_VIDEO).available)
    check(
        "o toque simples passa a usar o app",
        CallOptions.best(completo)?.method == CallMethod.APP_VIDEO,
    )
    check(
        "WhatsApp NAO desaparece quando o app funciona",
        opt(completo, CallMethod.WHATSAPP_VIDEO).available,
    )

    // Contato sem telefone: so resta o app.
    val semTelefone = options(phone = null)
    check("sem telefone, WhatsApp fica indisponivel", !opt(semTelefone, CallMethod.WHATSAPP_VIDEO).available)
    check(
        "e explica que falta o telefone",
        opt(semTelefone, CallMethod.WHATSAPP_VIDEO).unavailableReason == UnavailableReason.NO_PHONE_NUMBER,
    )
    check("telefone invalido conta como ausente", !opt(options(phone = "123"), CallMethod.PHONE_DIAL).available)

    // Sem par, sem presenca, ja em chamada.
    check(
        "sem pareamento, app indisponivel",
        opt(options(paired = null), CallMethod.APP_VIDEO).unavailableReason == UnavailableReason.NO_PAIRING,
    )
    check(
        "par offline, app indisponivel",
        opt(options(online = false), CallMethod.APP_VIDEO).unavailableReason == UnavailableReason.PEER_OFFLINE,
    )
    check(
        "ja em chamada tem precedencia sobre os outros motivos",
        opt(options(inCall = true, configured = false), CallMethod.APP_VIDEO).unavailableReason
            == UnavailableReason.ALREADY_IN_CALL,
    )

    // Contato sem nada.
    val nada = options(phone = null, configured = false)
    check("contato sem telefone e sem app nao tem opcao", !CallOptions.hasAnyOption(nada))
    check("e best() devolve null", CallOptions.best(nada) == null)

    // Toda opcao indisponivel precisa dizer por que.
    check(
        "toda opcao indisponivel tem explicacao",
        (hoje + semTelefone + nada).filter { !it.available }.all { !it.explanation.isNullOrBlank() },
    )
    check("todo botao tem rotulo", completo.all { it.label.isNotBlank() })
}

fun main() {
    println("=== Verificacao do alinhamento com a especificacao v3.1 ===\n")
    testSigning(); println()
    testSafetyNumber(); println()
    testFreshness(); println()
    testSchema(); println()
    testPhoneNormalization(); println()
    testCallOptions(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
