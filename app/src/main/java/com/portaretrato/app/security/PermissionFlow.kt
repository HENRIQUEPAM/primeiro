package com.portaretrato.app.security

/** Permissões sensíveis do app, com o texto que justifica cada uma. */
enum class SensitivePermission(
    val androidPermission: String,
    val title: String,
    val rationale: String,
) {
    CAMERA(
        androidPermission = "android.permission.CAMERA",
        title = "Usar a câmera",
        rationale = "A câmera é usada para a videochamada com a sua família. " +
            "Sempre que ela estiver ligada, você verá um aviso na tela. " +
            "Ela nunca liga sozinha.",
    ),
    MICROPHONE(
        androidPermission = "android.permission.RECORD_AUDIO",
        title = "Usar o microfone",
        rationale = "O microfone é usado só durante a videochamada, para a " +
            "outra pessoa ouvir você.",
    ),
    NOTIFICATIONS(
        androidPermission = "android.permission.POST_NOTIFICATIONS",
        title = "Mostrar avisos",
        rationale = "Os avisos mostram quando alguém está ligando e quando a " +
            "câmera está ligada. Sem eles, o aviso de câmera não aparece.",
    ),
}

/** O que fazer diante de uma permissão. */
sealed interface PermissionStep {
    /** Já concedida: siga. */
    data object Proceed : PermissionStep

    /** Explique antes de abrir o diálogo do sistema. */
    data class ShowRationale(val permission: SensitivePermission) : PermissionStep

    /** Peça diretamente ao sistema. */
    data class Request(val permission: SensitivePermission) : PermissionStep

    /**
     * O usuário negou permanentemente. O diálogo do sistema não aparece mais;
     * só as configurações do aparelho resolvem.
     */
    data class OpenSettings(val permission: SensitivePermission) : PermissionStep
}

/**
 * Decide como pedir uma permissão.
 *
 * Lógica pura, testável. O ponto de projeto: **nunca disparar o diálogo do
 * sistema sem contexto**. Um diálogo "Permitir que o app use a câmera?" que
 * aparece do nada, para uma pessoa idosa, tem uma resposta previsível — não. E
 * um "não" precipitado no Android 11+ vira negação permanente depois de duas
 * vezes, transformando um susto em um recurso quebrado para sempre.
 *
 * Então a ordem é: explicar em português por que, e só depois perguntar.
 */
object PermissionFlow {

    /**
     * @param granted resultado de `checkSelfPermission`.
     * @param shouldShowRationale resultado de `shouldShowRequestPermissionRationale`.
     * @param alreadyAsked se o app já pediu esta permissão alguma vez.
     */
    fun next(
        permission: SensitivePermission,
        granted: Boolean,
        shouldShowRationale: Boolean,
        alreadyAsked: Boolean,
    ): PermissionStep = when {
        granted -> PermissionStep.Proceed

        // O sistema diz para explicar: o usuário já negou uma vez.
        shouldShowRationale -> PermissionStep.ShowRationale(permission)

        // Já pedimos antes, o sistema não quer mais mostrar o diálogo e não
        // pede explicação: negação permanente. Só as configurações resolvem.
        alreadyAsked -> PermissionStep.OpenSettings(permission)

        // Primeira vez: explicamos mesmo assim, antes de perguntar. Esta é a
        // diferença em relação ao comportamento comum — a maioria dos apps
        // dispara o diálogo direto aqui.
        else -> PermissionStep.ShowRationale(permission)
    }

    /**
     * Permissões necessárias para uma videochamada. Notificação entra porque
     * sem ela o aviso de câmera não aparece, e a política do app é não abrir a
     * câmera em silêncio.
     */
    fun forVideoCall(): List<SensitivePermission> = listOf(
        SensitivePermission.CAMERA,
        SensitivePermission.MICROPHONE,
        SensitivePermission.NOTIFICATIONS,
    )

    /**
     * A varredura de rostos lê fotos já salvas: **não precisa de câmera**.
     *
     * Registrado explicitamente porque é fácil errar — associar reconhecimento
     * facial a "precisa de câmera" é o reflexo natural, e pediria uma permissão
     * perigosa sem necessidade nenhuma.
     */
    fun forFaceRecognition(): List<SensitivePermission> = emptyList()
}
