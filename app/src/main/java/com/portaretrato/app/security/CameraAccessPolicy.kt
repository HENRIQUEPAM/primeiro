package com.portaretrato.app.security

/** Para que a camera esta sendo pedida. Nao ha proposito generico de proposito. */
enum class CameraPurpose(
    /** Texto mostrado ao usuario. Obrigatorio: nao existe acesso sem justificativa visivel. */
    val userVisibleReason: String,
) {
    /** Varredura de rostos nas fotos do acervo. */
    FACE_RECOGNITION("Reconhecendo rostos nas suas fotos"),

    /** Chamada de video em andamento. */
    VIDEO_CALL("Chamada de video em andamento"),

    /** Cadastro de rosto com a camera, quando existir. */
    FACE_ENROLLMENT("Cadastrando um rosto"),
}

/** Em que estado de ciclo de vida o app esta quando pede a camera. */
enum class AppVisibility {
    /** Ha uma Activity visivel. */
    FOREGROUND,

    /**
     * Sem Activity visivel, mas com foreground service de camera ativo e
     * notificacao visivel.
     */
    FOREGROUND_SERVICE,

    /** Nada visivel para o usuario. */
    BACKGROUND,
}

/** Por que um pedido de camera foi negado. */
enum class CameraDenialReason {
    /** CAMERA nao concedida pelo usuario. */
    PERMISSION_NOT_GRANTED,

    /** Outro consumidor ja detem a camera. */
    ALREADY_IN_USE,

    /** App em segundo plano sem nada visivel — acesso invisivel e proibido. */
    NOT_VISIBLE_TO_USER,

    /** Usuario desligou a funcionalidade nas configuracoes. */
    DISABLED_BY_USER,

    /** Aparelho sem camera. */
    NO_CAMERA_HARDWARE,
}

/** Resultado da decisao. */
sealed interface CameraDecision {
    data class Allow(val purpose: CameraPurpose, val requiresNotice: Boolean) : CameraDecision
    data class Deny(val reason: CameraDenialReason) : CameraDecision
}

/**
 * Decide se um pedido de camera pode ser atendido.
 *
 * Logica pura, sem Android: roda em teste de unidade e pode ser auditada
 * lendo um arquivo so. Toda a politica de privacidade da camera do aplicativo
 * esta aqui — nao espalhada por Activities e Services.
 *
 * ## Principios
 *
 * 1. **Falha fechada.** Qualquer condicao nao satisfeita nega. Nao existe
 *    caminho "na duvida, deixa passar".
 * 2. **Nunca invisivel.** Acesso com o app em BACKGROUND e sempre negado. Para
 *    usar a camera sem Activity na tela e obrigatorio um foreground service
 *    com notificacao — que o proprio Android exige desde a API 29, e que aqui
 *    e reforcado na politica em vez de depender so do sistema.
 * 3. **Um consumidor por vez.** O aparelho tem uma camera fisica. Deixar
 *    reconhecimento e chamada disputarem gera CameraAccessException e, pior,
 *    torna impossivel dizer ao usuario quem esta usando a camera.
 * 4. **Sempre com aviso.** Todo acesso concedido exige aviso visivel. Nao ha
 *    proposito isento.
 */
object CameraAccessPolicy {

    /**
     * @param purpose para que a camera e pedida.
     * @param permissionGranted resultado real de checkSelfPermission.
     * @param visibility estado do app no momento do pedido.
     * @param currentHolder proposito que ja detem a camera, se houver.
     * @param userEnabled o usuario mantem esse proposito ligado nas configuracoes.
     * @param hasCameraHardware o aparelho tem camera.
     */
    fun evaluate(
        purpose: CameraPurpose,
        permissionGranted: Boolean,
        visibility: AppVisibility,
        currentHolder: CameraPurpose?,
        userEnabled: Boolean,
        hasCameraHardware: Boolean,
    ): CameraDecision {
        // A ordem das checagens e deliberada: primeiro o que e imutavel
        // (hardware), depois o que o usuario decidiu (permissao, preferencia),
        // depois o estado momentaneo (posse, visibilidade). Isso faz a mensagem
        // de negacao ser sempre a mais util para o usuario.
        if (!hasCameraHardware) {
            return CameraDecision.Deny(CameraDenialReason.NO_CAMERA_HARDWARE)
        }
        if (!permissionGranted) {
            return CameraDecision.Deny(CameraDenialReason.PERMISSION_NOT_GRANTED)
        }
        if (!userEnabled) {
            return CameraDecision.Deny(CameraDenialReason.DISABLED_BY_USER)
        }
        if (currentHolder != null && currentHolder != purpose) {
            return CameraDecision.Deny(CameraDenialReason.ALREADY_IN_USE)
        }
        if (visibility == AppVisibility.BACKGROUND) {
            return CameraDecision.Deny(CameraDenialReason.NOT_VISIBLE_TO_USER)
        }
        // requiresNotice e sempre true. O parametro existe para deixar
        // explicito no tipo que nenhuma concessao sai sem aviso — se um dia
        // alguem quiser uma excecao, vai ter de mudar esta linha e o teste que
        // a trava.
        return CameraDecision.Allow(purpose, requiresNotice = true)
    }

    /**
     * Chamada tem prioridade sobre reconhecimento facial.
     *
     * Uma chamada e sincrona e o usuario esta esperando; a varredura de rostos
     * e diferivel e sera reenfileirada. Serve tambem ao requisito de camera
     * unica: em vez de disputar, o reconhecimento cede.
     */
    fun shouldPreempt(current: CameraPurpose, incoming: CameraPurpose): Boolean =
        current == CameraPurpose.FACE_RECOGNITION && incoming == CameraPurpose.VIDEO_CALL

    /** Mensagem para o usuario quando o acesso e negado. */
    fun explain(reason: CameraDenialReason): String = when (reason) {
        CameraDenialReason.PERMISSION_NOT_GRANTED ->
            "O aplicativo precisa da sua permissao para usar a camera."
        CameraDenialReason.ALREADY_IN_USE ->
            "A camera ja esta sendo usada agora."
        CameraDenialReason.NOT_VISIBLE_TO_USER ->
            "A camera so pode ser usada com o aplicativo aberto."
        CameraDenialReason.DISABLED_BY_USER ->
            "Voce desligou esta funcao nas configuracoes."
        CameraDenialReason.NO_CAMERA_HARDWARE ->
            "Este aparelho nao tem camera."
    }
}
