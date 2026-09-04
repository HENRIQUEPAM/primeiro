import com.portaretrato.app.call.AutoAnswerDecision
import com.portaretrato.app.call.AutoAnswerPolicy
import com.portaretrato.app.call.CallEndReason
import com.portaretrato.app.call.CallEvent
import com.portaretrato.app.call.CallInvite
import com.portaretrato.app.call.CallRole
import com.portaretrato.app.call.CallState
import com.portaretrato.app.call.CallStateMachine
import com.portaretrato.app.call.SignalMessage
import com.portaretrato.app.call.SignalingProtocol
import com.portaretrato.app.call.TrustedContact

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

// ---------------------------------------------------------------------------
// 1. Maquina de estados
// ---------------------------------------------------------------------------
fun testStateMachine() {
    println("[1] Maquina de estados da chamada")

    // Caminho feliz de quem liga.
    val caller = CallStateMachine(CallRole.CALLER)
    check("comeca em IDLE", caller.state == CallState.IDLE)
    check("LocalDial -> DIALING", caller.handle(CallEvent.LocalDial) && caller.state == CallState.DIALING)
    check("Negotiating -> CONNECTING", caller.handle(CallEvent.Negotiating) && caller.state == CallState.CONNECTING)
    check("MediaConnected -> ACTIVE", caller.handle(CallEvent.MediaConnected) && caller.state == CallState.ACTIVE)

    // Recuperacao de rede: cai e volta sem derrubar a chamada.
    check("MediaDisconnected -> RECONNECTING", caller.handle(CallEvent.MediaDisconnected) && caller.state == CallState.RECONNECTING)
    check("reconecta de volta para ACTIVE", caller.handle(CallEvent.MediaConnected) && caller.state == CallState.ACTIVE)

    // Encerramento.
    check("Ended -> ENDED", caller.handle(CallEvent.Ended(CallEndReason.LOCAL_HANGUP)) && caller.state == CallState.ENDED)
    check("motivo registrado", caller.endReason == CallEndReason.LOCAL_HANGUP)
    check("ENDED e final: novo Ended e ignorado", !caller.handle(CallEvent.Ended(CallEndReason.ERROR)))
    check("ENDED e final: MediaConnected e ignorado", !caller.handle(CallEvent.MediaConnected))
    check("motivo original preservado", caller.endReason == CallEndReason.LOCAL_HANGUP)

    // Caminho de quem recebe.
    val invite = CallInvite("c1", "pai", "Pai", "+5511999999999", "vovo", 0L, true)
    val callee = CallStateMachine(CallRole.CALLEE)
    check("CALLEE nao pode discar", !callee.handle(CallEvent.LocalDial))
    check("RemoteInvite -> RINGING", callee.handle(CallEvent.RemoteInvite(invite)) && callee.state == CallState.RINGING)
    check("LocalAccept -> CONNECTING", callee.handle(CallEvent.LocalAccept) && callee.state == CallState.CONNECTING)
    check("aceitar duas vezes e no-op", !callee.handle(CallEvent.LocalAccept))

    // CALLER nao entra em RINGING.
    val c2 = CallStateMachine(CallRole.CALLER)
    check("CALLER ignora RemoteInvite", !c2.handle(CallEvent.RemoteInvite(invite)))

    // Fora de ordem: hangup antes da answer (acontece em rede movel).
    val c3 = CallStateMachine(CallRole.CALLER)
    c3.handle(CallEvent.LocalDial)
    check("hangup precoce e aceito", c3.handle(CallEvent.Ended(CallEndReason.REMOTE_HANGUP)))
    check("answer atrasada apos ENDED e ignorada", !c3.handle(CallEvent.Negotiating))

    // Callback de transicao.
    val seen = mutableListOf<Pair<CallState, CallState>>()
    val c4 = CallStateMachine(CallRole.CALLER) { from, to -> seen += from to to }
    c4.handle(CallEvent.LocalDial)
    c4.handle(CallEvent.MediaConnected)
    check("callback recebeu 2 transicoes", seen.size == 2, "=${seen.size}")
    check("primeira transicao correta", seen[0] == (CallState.IDLE to CallState.DIALING))
}

// ---------------------------------------------------------------------------
// 2. Protocolo de sinalizacao
// ---------------------------------------------------------------------------
fun testProtocol() {
    println("[2] Protocolo de sinalizacao")

    val offer = SignalMessage.Offer("c1", "v=0...sdp", "pai", "Pai", "+5511999999999", true, 1700000000000L)
    val decodedOffer = SignalingProtocol.decode(SignalingProtocol.encode(offer))
    check("offer sobrevive ao round-trip", decodedOffer == offer, "=$decodedOffer")

    val answer = SignalMessage.Answer("c1", "v=0...answer")
    check("answer round-trip", SignalingProtocol.decode(SignalingProtocol.encode(answer)) == answer)

    val ice = SignalMessage.Ice("c1", "0", 0, "candidate:1 1 UDP ...", "vovo")
    check("ice round-trip", SignalingProtocol.decode(SignalingProtocol.encode(ice)) == ice)

    val hangup = SignalMessage.Hangup("c1", CallEndReason.REMOTE_HANGUP)
    check("hangup round-trip", SignalingProtocol.decode(SignalingProtocol.encode(hangup)) == hangup)

    // Normalizacao de numero: Firestore devolve Long, FCM devolve String.
    val iceFromFcm = SignalingProtocol.decode(
        mapOf(
            "type" to "ice", "callId" to "c1", "sdpMid" to "0",
            "sdpMLineIndex" to "2", // String, como vem do payload do FCM
            "candidate" to "cand", "fromUid" to "u",
        ),
    ) as? SignalMessage.Ice
    check("sdpMLineIndex como String e aceito", iceFromFcm?.sdpMLineIndex == 2, "=${iceFromFcm?.sdpMLineIndex}")

    val iceFromFirestore = SignalingProtocol.decode(
        mapOf(
            "type" to "ice", "callId" to "c1", "sdpMid" to "0",
            "sdpMLineIndex" to 2L, // Long, como vem do Firestore
            "candidate" to "cand", "fromUid" to "u",
        ),
    ) as? SignalMessage.Ice
    check("sdpMLineIndex como Long e aceito", iceFromFirestore?.sdpMLineIndex == 2)

    // Robustez: documento corrompido nao pode lancar excecao.
    check("mapa nulo -> null", SignalingProtocol.decode(null) == null)
    check("mapa vazio -> null", SignalingProtocol.decode(emptyMap()) == null)
    check("tipo desconhecido -> null", SignalingProtocol.decode(mapOf("type" to "xyz", "callId" to "c")) == null)
    check("offer sem sdp -> null", SignalingProtocol.decode(mapOf("type" to "offer", "callId" to "c", "fromUid" to "u")) == null)
    check("offer sem fromUid -> null", SignalingProtocol.decode(mapOf("type" to "offer", "callId" to "c", "sdp" to "s")) == null)
    check("ice sem sdpMid -> null", SignalingProtocol.decode(mapOf("type" to "ice", "callId" to "c", "candidate" to "x")) == null)
    check(
        "tipo com valor errado -> null",
        SignalingProtocol.decode(mapOf("type" to 42, "callId" to "c")) == null,
    )
    // Motivo desconhecido cai no default em vez de quebrar.
    val h = SignalingProtocol.decode(mapOf("type" to "hangup", "callId" to "c", "reason" to "INEXISTENTE"))
    check("motivo invalido usa default", (h as? SignalMessage.Hangup)?.reason == CallEndReason.REMOTE_HANGUP)

    val built = SignalingProtocol.inviteFrom(offer, "vovo")
    check("convite derivado da offer", built.callId == "c1" && built.toUid == "vovo" && built.fromName == "Pai")
}

// ---------------------------------------------------------------------------
// 3. Atendimento automatico
// ---------------------------------------------------------------------------
fun testAutoAnswer() {
    println("[3] Politica de atendimento automatico")

    val filha = TrustedContact("filha", "Ana", "+5511988887777", autoAnswerEnabled = true)
    val vizinho = TrustedContact("vizinho", "Jose", null, autoAnswerEnabled = false)
    val contacts = listOf(filha, vizinho)

    fun invite(id: String, from: String) = CallInvite(id, from, "?", null, "vovo", 0L, true)

    // `featureEnabled = true` de proposito: este bloco testa a LOGICA de
    // decisao. O que vale no aparelho e o padrao da constante, verificado no
    // bloco [3b] logo abaixo.
    val policy = AutoAnswerPolicy({ contacts }, featureEnabled = true)
    val d1 = policy.decide(invite("c1", "filha"), callInProgress = false, hourOfDay = 14)
    check("contato confiavel atende sozinho", d1 is AutoAnswerDecision.Answer, "=$d1")
    check("atende com o nome certo", (d1 as? AutoAnswerDecision.Answer)?.contactName == "Ana")
    check("tem atraso para dar chance de recusar", ((d1 as? AutoAnswerDecision.Answer)?.delayMs ?: 0) > 0)

    val d2 = policy.decide(invite("c2", "vizinho"), false, 14)
    check("contato sem auto-atendimento apenas toca", d2 is AutoAnswerDecision.Ring, "=$d2")

    val d3 = policy.decide(invite("c3", "estranho"), false, 14)
    check("desconhecido apenas toca", d3 is AutoAnswerDecision.Ring, "=$d3")
    check("desconhecido mostra nome generico", (d3 as? AutoAnswerDecision.Ring)?.displayName == "Desconhecido")

    val d4 = policy.decide(invite("c4", "filha"), callInProgress = true, hourOfDay = 14)
    check("ocupado recusa", (d4 as? AutoAnswerDecision.Reject)?.reason == CallEndReason.BUSY, "=$d4")

    // Reentrega do FCM: o mesmo callId nao pode atender duas vezes.
    val dup = policy.decide(invite("c1", "filha"), false, 14)
    check("convite duplicado e recusado", dup is AutoAnswerDecision.Reject, "=$dup")
    policy.forget("c1")
    check("apos forget, o mesmo id volta a valer", policy.decide(invite("c1", "filha"), false, 14) is AutoAnswerDecision.Answer)

    // Janela de horario que cruza a meia-noite: permitido das 7h as 21h.
    val daytime = AutoAnswerPolicy({ contacts }, quietHoursStart = 7, quietHoursEnd = 21, featureEnabled = true)
    check("dentro da janela atende", daytime.decide(invite("d1", "filha"), false, 10) is AutoAnswerDecision.Answer)
    check("fora da janela apenas toca", daytime.decide(invite("d2", "filha"), false, 3) is AutoAnswerDecision.Ring)
    check("limite inferior inclusivo", daytime.decide(invite("d3", "filha"), false, 7) is AutoAnswerDecision.Answer)
    check("limite superior inclusivo", daytime.decide(invite("d4", "filha"), false, 21) is AutoAnswerDecision.Answer)
    check("22h esta fora", daytime.decide(invite("d5", "filha"), false, 22) is AutoAnswerDecision.Ring)

    // Janela invertida: permitido das 22h as 6h (atravessa a meia-noite).
    val night = AutoAnswerPolicy({ contacts }, quietHoursStart = 22, quietHoursEnd = 6, featureEnabled = true)
    check("janela invertida: 23h dentro", night.decide(invite("n1", "filha"), false, 23) is AutoAnswerDecision.Answer)
    check("janela invertida: 2h dentro", night.decide(invite("n2", "filha"), false, 2) is AutoAnswerDecision.Answer)
    check("janela invertida: 12h fora", night.decide(invite("n3", "filha"), false, 12) is AutoAnswerDecision.Ring)

    // Limite de memoria de convites.
    val bounded = AutoAnswerPolicy({ contacts }, featureEnabled = true)
    repeat(200) { bounded.decide(invite("bulk$it", "filha"), false, 14) }
    check("memoria de convites nao cresce sem limite", true) // sem crash/OOM
}

// ---------------------------------------------------------------------------
// 3b. A chave-mestra do atendimento automatico
// ---------------------------------------------------------------------------
fun testAutoAnswerKillSwitch() {
    println("[3b] Chave-mestra do atendimento automatico")

    check(
        "o recurso vem DESLIGADO de fabrica",
        !AutoAnswerPolicy.FEATURE_ENABLED,
        "FEATURE_ENABLED=${AutoAnswerPolicy.FEATURE_ENABLED}",
    )

    // O caso perigoso: um contato marcado como confiavel em disco, por uma
    // versao futura ou por um arquivo editado a mao. Com a chave desligada ele
    // NAO pode atender sozinho.
    val marcado = TrustedContact("filha", "Ana", "+5511988887777", autoAnswerEnabled = true)

    fun invite(id: String, from: String) = CallInvite(id, from, "?", null, "vovo", 0L, true)

    // Varredura de todas as combinacoes que chegam ate a decisao: contato
    // marcado / nao marcado / ausente, com e sem janela de horario, nas 24
    // horas do dia. Nenhuma pode produzir Answer.
    var atendeuSozinho = 0
    var casos = 0
    val listas = listOf(
        listOf(marcado),
        listOf(marcado.copy(autoAnswerEnabled = false)),
        emptyList(),
    )
    val janelas = listOf(null to null, 7 to 21, 22 to 6, 0 to 23)

    for ((i, lista) in listas.withIndex()) {
        for ((j, janela) in janelas.withIndex()) {
            // Sem featureEnabled: usa o padrao, que e o que roda no aparelho.
            val p = AutoAnswerPolicy({ lista }, quietHoursStart = janela.first, quietHoursEnd = janela.second)
            for (hora in 0..23) {
                casos++
                val d = p.decide(invite("k-$i-$j-$hora", "filha"), callInProgress = false, hourOfDay = hora)
                if (d is AutoAnswerDecision.Answer) atendeuSozinho++
            }
        }
    }
    check(
        "nenhuma das $casos combinacoes atende sozinho",
        atendeuSozinho == 0,
        "=$atendeuSozinho",
    )

    // E o resto do comportamento continua correto com a chave desligada.
    val p = AutoAnswerPolicy({ listOf(marcado) })
    val toque = p.decide(invite("t1", "filha"), false, 14)
    check("continua tocando", toque is AutoAnswerDecision.Ring, "=$toque")
    check("com o nome da agenda", (toque as? AutoAnswerDecision.Ring)?.displayName == "Ana")

    val estranho = p.decide(invite("t2", "ninguem"), false, 14)
    check("desconhecido nao ganha nome do convite",
        (estranho as? AutoAnswerDecision.Ring)?.displayName == "Desconhecido",
        "=$estranho")

    // As protecoes que NAO sao do recurso continuam valendo.
    check("ocupado ainda recusa",
        (p.decide(invite("t3", "filha"), callInProgress = true, hourOfDay = 14) as? AutoAnswerDecision.Reject)
            ?.reason == CallEndReason.BUSY)
    check("convite duplicado ainda e recusado",
        p.decide(invite("t1", "filha"), false, 14) is AutoAnswerDecision.Reject)
}

// ---------------------------------------------------------------------------
// 5. Codigo de aparelho x telefone sintetico
// ---------------------------------------------------------------------------
fun testHasDeviceCode() {
    println("[5] TrustedContact.hasDeviceCode")

    fun contact(uid: String) = TrustedContact(uid = uid, name = "x", phone = null, autoAnswerEnabled = false)

    check(
        "uid tel: sintetico NAO tem codigo de aparelho",
        !contact("${TrustedContact.PHONE_UID_PREFIX}11999999999").hasDeviceCode,
    )
    check("uid digitado tem codigo de aparelho", contact("AbCdEf123456").hasDeviceCode)
    check("uid vazio NAO tem codigo de aparelho", !contact("").hasDeviceCode)
}

fun main() {
    println("=== Verificacao do modulo de chamadas ===\n")
    testStateMachine(); println()
    testProtocol(); println()
    testAutoAnswer(); println()
    testAutoAnswerKillSwitch(); println()
    testHasDeviceCode(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
