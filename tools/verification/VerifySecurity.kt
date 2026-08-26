import com.portaretrato.app.security.AppVisibility
import com.portaretrato.app.security.CameraAccessPolicy
import com.portaretrato.app.security.CameraAuditEntry
import com.portaretrato.app.security.CameraAuditLog
import com.portaretrato.app.security.CameraDecision
import com.portaretrato.app.security.CameraDenialReason
import com.portaretrato.app.security.CameraPurpose
import com.portaretrato.app.security.PermissionFlow
import com.portaretrato.app.security.PermissionStep
import com.portaretrato.app.security.FieldCrypto
import com.portaretrato.app.security.SensitivePermission
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

/** Pedido "tudo certo", sobre o qual variamos uma condicao de cada vez. */
fun evaluate(
    purpose: CameraPurpose = CameraPurpose.VIDEO_CALL,
    permission: Boolean = true,
    visibility: AppVisibility = AppVisibility.FOREGROUND,
    holder: CameraPurpose? = null,
    enabled: Boolean = true,
    hardware: Boolean = true,
) = CameraAccessPolicy.evaluate(purpose, permission, visibility, holder, enabled, hardware)

fun denialOf(d: CameraDecision) = (d as? CameraDecision.Deny)?.reason

// ---------------------------------------------------------------------------
// 1. A garantia central: sem permissao, sem camera
// ---------------------------------------------------------------------------
fun testPermissionIsMandatory() {
    println("[1] Nunca acessa a camera sem permissao")

    // Para CADA proposito, sem excecao.
    for (purpose in CameraPurpose.entries) {
        val decision = evaluate(purpose = purpose, permission = false)
        check(
            "$purpose sem permissao e NEGADO",
            denialOf(decision) == CameraDenialReason.PERMISSION_NOT_GRANTED,
            "=$decision",
        )
    }

    // Nem mesmo com todo o resto favoravel.
    check(
        "sem permissao nega mesmo em primeiro plano, camera livre e funcao ligada",
        evaluate(permission = false) is CameraDecision.Deny,
    )

    // A permissao e a PRIMEIRA coisa checada depois do hardware: a mensagem
    // mostrada ao usuario precisa ser a acionavel.
    check(
        "sem permissao E camera ocupada -> reporta a permissao",
        denialOf(evaluate(permission = false, holder = CameraPurpose.VIDEO_CALL))
            == CameraDenialReason.PERMISSION_NOT_GRANTED,
    )
    check(
        "sem permissao E em segundo plano -> reporta a permissao",
        denialOf(evaluate(permission = false, visibility = AppVisibility.BACKGROUND))
            == CameraDenialReason.PERMISSION_NOT_GRANTED,
    )
}

// ---------------------------------------------------------------------------
// 2. Nunca em silencio
// ---------------------------------------------------------------------------
fun testNeverSilent() {
    println("[2] Nenhuma concessao sai sem aviso")

    var allRequireNotice = true
    for (purpose in CameraPurpose.entries) {
        val decision = evaluate(purpose = purpose)
        val allow = decision as? CameraDecision.Allow
        if (allow == null || !allow.requiresNotice) allAllow@ run { allRequireNotice = false }
    }
    check("TODO proposito concedido exige aviso", allRequireNotice)

    check(
        "todo proposito tem justificativa em portugues",
        CameraPurpose.entries.all { it.userVisibleReason.isNotBlank() && it.userVisibleReason.length > 10 },
    )
}

// ---------------------------------------------------------------------------
// 3. Nunca invisivel
// ---------------------------------------------------------------------------
fun testNeverInvisible() {
    println("[3] Nunca acessa a camera de forma invisivel")

    check(
        "app em segundo plano e NEGADO",
        denialOf(evaluate(visibility = AppVisibility.BACKGROUND))
            == CameraDenialReason.NOT_VISIBLE_TO_USER,
    )
    check(
        "segundo plano nega ate para chamada de video",
        evaluate(purpose = CameraPurpose.VIDEO_CALL, visibility = AppVisibility.BACKGROUND)
            is CameraDecision.Deny,
    )
    check(
        "com foreground service (notificacao visivel) e PERMITIDO",
        evaluate(visibility = AppVisibility.FOREGROUND_SERVICE) is CameraDecision.Allow,
    )
    check(
        "com Activity visivel e PERMITIDO",
        evaluate(visibility = AppVisibility.FOREGROUND) is CameraDecision.Allow,
    )
}

// ---------------------------------------------------------------------------
// 4. Um consumidor por vez, e a preempcao
// ---------------------------------------------------------------------------
fun testExclusivity() {
    println("[4] Exclusividade da camera")

    check(
        "camera ocupada por outro proposito e NEGADA",
        denialOf(evaluate(purpose = CameraPurpose.VIDEO_CALL, holder = CameraPurpose.FACE_RECOGNITION))
            == CameraDenialReason.ALREADY_IN_USE,
    )
    check(
        "reentrancia do MESMO proposito e permitida",
        evaluate(purpose = CameraPurpose.VIDEO_CALL, holder = CameraPurpose.VIDEO_CALL)
            is CameraDecision.Allow,
    )

    // Prioridade: a chamada tira a varredura, nunca o contrario.
    check(
        "chamada toma a camera do reconhecimento",
        CameraAccessPolicy.shouldPreempt(CameraPurpose.FACE_RECOGNITION, CameraPurpose.VIDEO_CALL),
    )
    check(
        "reconhecimento NAO toma a camera da chamada",
        !CameraAccessPolicy.shouldPreempt(CameraPurpose.VIDEO_CALL, CameraPurpose.FACE_RECOGNITION),
    )
    check(
        "chamada nao toma de outra chamada",
        !CameraAccessPolicy.shouldPreempt(CameraPurpose.VIDEO_CALL, CameraPurpose.VIDEO_CALL),
    )
}

// ---------------------------------------------------------------------------
// 5. Preferencia do usuario e hardware
// ---------------------------------------------------------------------------
fun testUserControl() {
    println("[5] Controle do usuario e hardware")

    check(
        "funcao desligada pelo usuario e NEGADA",
        denialOf(evaluate(enabled = false)) == CameraDenialReason.DISABLED_BY_USER,
    )
    check(
        "aparelho sem camera e NEGADO",
        denialOf(evaluate(hardware = false)) == CameraDenialReason.NO_CAMERA_HARDWARE,
    )
    check(
        "sem hardware tem precedencia sobre falta de permissao",
        denialOf(evaluate(hardware = false, permission = false))
            == CameraDenialReason.NO_CAMERA_HARDWARE,
    )
    check(
        "toda negacao tem explicacao em portugues",
        CameraDenialReason.entries.all { CameraAccessPolicy.explain(it).length > 15 },
    )
}

// ---------------------------------------------------------------------------
// 6. Falha fechada: forca bruta sobre todas as combinacoes
// ---------------------------------------------------------------------------
fun testFailsClosed() {
    println("[6] Falha fechada (todas as combinacoes)")

    var total = 0
    var allowed = 0
    var wrongAllow = 0

    for (purpose in CameraPurpose.entries) {
        for (permission in listOf(true, false)) {
            for (visibility in AppVisibility.entries) {
                for (holder in listOf<CameraPurpose?>(null) + CameraPurpose.entries) {
                    for (enabled in listOf(true, false)) {
                        for (hardware in listOf(true, false)) {
                            total++
                            val decision = evaluate(purpose, permission, visibility, holder, enabled, hardware)
                            if (decision is CameraDecision.Allow) {
                                allowed++
                                // Um Allow so pode existir se TODAS as condicoes
                                // forem favoraveis. Qualquer outro Allow e um furo.
                                val legitimate = permission &&
                                    enabled &&
                                    hardware &&
                                    visibility != AppVisibility.BACKGROUND &&
                                    (holder == null || holder == purpose)
                                if (!legitimate) {
                                    wrongAllow++
                                    println("        FURO: $purpose perm=$permission vis=$visibility holder=$holder en=$enabled hw=$hardware")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    println("      $total combinacoes testadas, $allowed permitidas")
    check("nenhuma concessao indevida em $total combinacoes", wrongAllow == 0, "furos=$wrongAllow")
    check("a politica realmente permite algo (nao trava tudo)", allowed > 0)
}

// ---------------------------------------------------------------------------
// 7. Trilha de auditoria
// ---------------------------------------------------------------------------
fun testAuditLog() {
    println("[7] Trilha de auditoria")

    val log = CameraAuditLog(capacity = 5)
    val t0 = 1_700_000_000_000L

    log.record(CameraAuditEntry(t0, CameraPurpose.VIDEO_CALL, CameraAuditEntry.Event.REQUESTED))
    log.record(CameraAuditEntry(t0, CameraPurpose.VIDEO_CALL, CameraAuditEntry.Event.GRANTED))
    log.record(
        CameraAuditEntry(t0 + 30_000, CameraPurpose.VIDEO_CALL, CameraAuditEntry.Event.RELEASED, durationMs = 30_000),
    )

    check("registra os eventos", log.recent().size == 3)
    check("mais recente primeiro", log.recent().first().event == CameraAuditEntry.Event.RELEASED)
    check("soma o tempo de uso", log.totalUsageMs(since = t0) == 30_000L, "=${log.totalUsageMs(t0)}")
    check("ignora uso anterior ao corte", log.totalUsageMs(since = t0 + 60_000) == 0L)

    log.record(
        CameraAuditEntry(
            t0, CameraPurpose.FACE_RECOGNITION, CameraAuditEntry.Event.DENIED,
            CameraDenialReason.PERMISSION_NOT_GRANTED,
        ),
    )
    check("conta negacoes por falta de permissao", log.permissionDenialCount() == 1)

    // Teto de memoria: o aparelho fica ligado por meses.
    repeat(50) { log.record(CameraAuditEntry(t0, CameraPurpose.VIDEO_CALL, CameraAuditEntry.Event.REQUESTED)) }
    check("respeita o teto de capacidade", log.recent().size == 5, "=${log.recent().size}")

    log.clear()
    check("limpa o historico", log.recent().isEmpty())
}

// ---------------------------------------------------------------------------
// 8. Fluxo de permissao
// ---------------------------------------------------------------------------
fun testPermissionFlow() {
    println("[8] Fluxo de pedido de permissao")

    val cam = SensitivePermission.CAMERA

    check(
        "ja concedida -> segue",
        PermissionFlow.next(cam, granted = true, shouldShowRationale = false, alreadyAsked = true)
            == PermissionStep.Proceed,
    )
    // O ponto de projeto: mesmo na primeira vez, explica antes de perguntar.
    check(
        "primeira vez -> EXPLICA antes do dialogo do sistema",
        PermissionFlow.next(cam, granted = false, shouldShowRationale = false, alreadyAsked = false)
            is PermissionStep.ShowRationale,
    )
    check(
        "negou uma vez -> explica",
        PermissionFlow.next(cam, granted = false, shouldShowRationale = true, alreadyAsked = true)
            is PermissionStep.ShowRationale,
    )
    check(
        "negacao permanente -> manda para as configuracoes",
        PermissionFlow.next(cam, granted = false, shouldShowRationale = false, alreadyAsked = true)
            is PermissionStep.OpenSettings,
    )

    check(
        "chamada de video pede camera, microfone e notificacao",
        PermissionFlow.forVideoCall().toSet() == setOf(
            SensitivePermission.CAMERA,
            SensitivePermission.MICROPHONE,
            SensitivePermission.NOTIFICATIONS,
        ),
    )
    // Reconhecimento le fotos salvas: nao ha razao nenhuma para pedir camera.
    check(
        "reconhecimento facial NAO pede permissao nenhuma",
        PermissionFlow.forFaceRecognition().isEmpty(),
    )

    check(
        "toda permissao tem justificativa em portugues",
        SensitivePermission.entries.all { it.rationale.length > 40 && it.title.isNotBlank() },
    )
}

// ---------------------------------------------------------------------------
// 9. Cifragem de campos sensiveis (AES-256-GCM)
// ---------------------------------------------------------------------------
fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

fun testFieldCrypto() {
    println("[9] Cifragem de campos sensiveis")

    val key = aesKey()
    val other = aesKey()

    // Embedding facial: dado biometrico.
    val embedding = ByteArray(768) { (it % 251).toByte() }
    val sealed = FieldCrypto.encrypt(embedding, key)

    check("round-trip preserva o embedding", FieldCrypto.decrypt(sealed, key)!!.contentEquals(embedding))
    check("texto cifrado difere do claro", !sealed.contentEquals(embedding))
    check("cresce so o cabecalho + tag (1+12+16)", sealed.size == embedding.size + 29, "=${sealed.size}")

    // A garantia que importa se o banco vazar.
    check("chave errada NAO decifra", FieldCrypto.decrypt(sealed, other) == null)

    // GCM autentica: um bit trocado invalida tudo.
    val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    check("adulteracao do texto cifrado e DETECTADA", FieldCrypto.decrypt(tampered, key) == null)
    val tamperedIv = sealed.copyOf().also { it[5] = (it[5] + 1).toByte() }
    check("adulteracao do IV e DETECTADA", FieldCrypto.decrypt(tamperedIv, key) == null)

    // IV reutilizado em GCM e falha catastrofica: tem de ser sempre novo.
    val ivs = (1..200).map { FieldCrypto.ivOf(FieldCrypto.encrypt(embedding, key))!!.toList() }
    check("IV nunca se repete em 200 operacoes", ivs.toSet().size == 200, "=${ivs.toSet().size}")
    check(
        "cifrar duas vezes o mesmo dado da saidas diferentes",
        !FieldCrypto.encrypt(embedding, key).contentEquals(FieldCrypto.encrypt(embedding, key)),
    )

    // Telefone.
    val phone = "+55 11 99999-9999"
    check("round-trip de texto", FieldCrypto.decryptString(FieldCrypto.encryptString(phone, key), key) == phone)

    // Robustez: registro corrompido nao pode derrubar a leitura do banco.
    check("entrada vazia devolve null", FieldCrypto.decrypt(ByteArray(0), key) == null)
    check("entrada truncada devolve null", FieldCrypto.decrypt(ByteArray(10), key) == null)
    check(
        "versao desconhecida devolve null",
        FieldCrypto.decrypt(sealed.copyOf().also { it[0] = 99 }, key) == null,
    )
    check("dado nao cifrado devolve null", FieldCrypto.decrypt(embedding, key) == null)
}

fun main() {
    println("=== Verificacao da seguranca ===\n")
    testPermissionIsMandatory(); println()
    testNeverSilent(); println()
    testNeverInvisible(); println()
    testExclusivity(); println()
    testUserControl(); println()
    testFailsClosed(); println()
    testAuditLog(); println()
    testPermissionFlow(); println()
    testFieldCrypto(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
