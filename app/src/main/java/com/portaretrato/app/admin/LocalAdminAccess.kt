package com.portaretrato.app.admin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Base64

/** Resultado de tentar cadastrar uma senha local neste aparelho. */
sealed interface LocalRegisterResult {
    data object Success : LocalRegisterResult

    /** Nem tentou: o aparelho não está em nenhuma rede Wi-Fi agora. */
    data object NotOnWifi : LocalRegisterResult

    /**
     * Está no Wi-Fi, mas o Android não entregou o nome da rede — falta a
     * permissão de localização, ou a localização do aparelho está desligada
     * (exigência do próprio Android a partir da versão 10, além da
     * permissão). Sem o nome da rede não há o que gravar como "rede de
     * casa".
     */
    data object NetworkNameUnavailable : LocalRegisterResult
}

/**
 * Senha local: cadastrada por CADA aparelho (ao contrário de
 * [AdminPassword], a senha global, compilada e igual em qualquer
 * instalação), e presa à rede Wi-Fi em que foi criada. Existe para a
 * garantia que foi pedida explicitamente: "provar que está dentro de casa,
 * não em outra casa" — mesmo que a senha vaze, ela só abre a babá
 * eletrônica de dentro da própria rede onde foi cadastrada.
 *
 * "Mesma rede" aqui é o nome (SSID) da rede Wi-Fi. Comparar só o tipo de
 * conexão (Wi-Fi sim/não) não bastaria — qualquer Wi-Fi, inclusive de
 * outra casa ou de um café, contaria como "rede de casa", que é
 * exatamente o que a garantia pedida queria evitar.
 */
class LocalAdminAccess(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Nome da rede em que a senha local foi cadastrada, para mostrar na
     * tela — `null` enquanto nenhuma senha local foi cadastrada ainda.
     */
    fun homeNetworkLabel(): String? = prefs.getString(KEY_SSID, null)

    fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        // getNetworkCapabilities() exige rede não-nula — sem aparelho nenhum
        // conectado (modo avião, por exemplo), activeNetwork vem null, e
        // passar isso adiante quebraria em tempo de execução.
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Cadastra [password] como a senha local, presa à rede Wi-Fi atual.
     * Precisa estar no Wi-Fi e o Android precisa entregar o nome da rede —
     * ver [LocalRegisterResult].
     */
    fun register(password: String): LocalRegisterResult {
        if (!isOnWifi()) return LocalRegisterResult.NotOnWifi
        val ssid = currentSsid() ?: return LocalRegisterResult.NetworkNameUnavailable

        val hash = PasswordHashing.hash(password)
        prefs.edit()
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString(KEY_SSID, ssid)
            .apply()
        return LocalRegisterResult.Success
    }

    /** A senha bate E o aparelho está na MESMA rede em que ela foi cadastrada. */
    fun verify(password: String): Boolean {
        val storedSsid = prefs.getString(KEY_SSID, null) ?: return false
        if (currentSsid() != storedSsid) return false
        return matchesStoredHash(password)
    }

    /**
     * A senha em si está certa, mas a rede não bate. Existe só para dar um
     * aviso melhor do que "senha incorreta" — sem isso, alguém tentando
     * entrar do 4G, fora de casa, ia achar que esqueceu a própria senha.
     */
    fun matchesButWrongNetwork(password: String): Boolean {
        val storedSsid = prefs.getString(KEY_SSID, null) ?: return false
        if (currentSsid() == storedSsid) return false
        return matchesStoredHash(password)
    }

    private fun matchesStoredHash(password: String): Boolean {
        val storedHash = prefs.getString(KEY_HASH, null) ?: return false
        val hash = try {
            Base64.decode(storedHash, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return PasswordHashing.matches(password, hash)
    }

    /**
     * SSID atual, sem as aspas que o Android coloca ao redor, ou `null` se
     * não estiver no Wi-Fi, ou se o sistema não entregar o nome (permissão
     * de localização negada, ou localização do aparelho desligada).
     */
    private fun currentSsid(): String? {
        if (!isOnWifi()) return null
        val wifiManager = appContext.getSystemService(WifiManager::class.java) ?: return null
        val raw = wifiManager.connectionInfo?.ssid ?: return null
        if (raw.isBlank() || raw == UNKNOWN_SSID) return null
        return raw.removeSurrounding("\"")
    }

    private companion object {
        const val PREFS = "local_admin_access"
        const val KEY_HASH = "password_hash"
        const val KEY_SSID = "home_ssid"

        /** O que WifiManager devolve sem permissão de localização concedida. */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
