package com.portaretrato.app.admin

/**
 * A senha de administrador que libera "Recursos avançados" — hoje, a babá
 * eletrônica (atendimento automático).
 *
 * **A MESMA senha vale em qualquer instalação deste app.** Não é mais uma
 * senha que cada aparelho escolhe para si (como numa primeira versão desta
 * tela) — é um código único, decidido por quem administra a família, que
 * funciona em todos os 10 (ou quantos forem) aparelhos que instalarem o
 * app a partir deste código-fonte. Pense nela como um código de ativação
 * de um recurso premium, não como uma senha de conta pessoal.
 *
 * Só o HASH fica aqui, nunca a senha em texto puro — mesmo sendo um
 * código compartilhado, e mesmo o app inteiro sendo de código aberto no
 * repositório, não há motivo para deixar a senha literalmente legível
 * para qualquer um que abra este arquivo.
 *
 * ## Como trocar a senha
 *
 * 1. Escolha a senha nova.
 * 2. Gere o hash dela: `PasswordHashing.hash("a senha escolhida")`,
 *    convertido para Base64 (`android.util.Base64.encodeToString(hash,
 *    Base64.NO_WRAP)` — ou peça para quem mantém o projeto fazer isso).
 * 3. Substitua [HASH_BASE64] pelo resultado.
 * 4. Publique um build novo — só instalações feitas a partir dele passam
 *    a aceitar a senha nova. Instalações antigas continuam com a senha
 *    antiga até serem atualizadas.
 *
 * `null` enquanto ninguém escolheu uma senha ainda: [AdminAccess.verify]
 * recusa qualquer tentativa, e a tela deixa isso explícito em vez de
 * fingir que existe uma senha para acertar.
 */
object AdminPassword {
    val HASH_BASE64: String? = null
}
