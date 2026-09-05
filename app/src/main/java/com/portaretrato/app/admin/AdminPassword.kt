package com.portaretrato.app.admin

/**
 * A senha de administrador GLOBAL: funciona em qualquer instalação deste
 * app, em qualquer rede — sem a restrição de rede que a senha LOCAL tem
 * (ver [LocalAdminAccess], cadastrada por aparelho, e só válida na rede
 * Wi-Fi onde foi criada).
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
 * 3. Substitua [GLOBAL_HASH_BASE64] pelo resultado.
 * 4. Publique um build novo — só instalações feitas a partir dele passam
 *    a aceitar a senha nova. Instalações antigas continuam com a senha
 *    antiga até serem atualizadas.
 */
object AdminPassword {
    /** Hash de "1.995.415.642" — ver [AdminAccess.verify]. */
    const val GLOBAL_HASH_BASE64: String = "AUc6ylpaidMdN7h7144fzomfY+8pXRQEDk4iEjQw3UioUcwAwFMIEAwszOW/4A2SJw=="
}
