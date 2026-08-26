package com.portaretrato.app.call

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Busca credenciais efemeras de TURN na Cloud Function `issueTurnCredentials`.
 *
 * A Secao 8 e direta: o CGNAT das operadoras residenciais brasileiras torna
 * TURN **obrigatorio na pratica, nao opcional**. Sem ele, uma fracao
 * significativa das chamadas simplesmente nao conecta — e o caso tipico do
 * produto (a filha ligando do celular na rua) e justamente o pior.
 *
 * Credenciais **nunca sao cacheadas entre sessoes**, conforme a Secao 7.2:
 * credencial HMAC expirada faz o ICE falhar em silencio, o que aparece para o
 * usuario como "a chamada nao completa" sem nenhuma mensagem de erro.
 *
 * Segredo de TURN nunca fica no APK: quem descompacta passaria a usar (e o dono
 * do projeto a pagar) o relay.
 */
class TurnCredentialsProvider(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
) {

    /**
     * Devolve a configuracao de ICE para uma chamada.
     *
     * Em caso de falha, cai para STUN apenas em vez de impedir a chamada: numa
     * rede domestica sem CGNAT ela ainda conecta, e o `ICEConnectionState.FAILED`
     * dispara o fallback para WhatsApp quando nao conectar.
     */
    suspend fun fetch(): CallConfig = try {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(FUNCTION_NAME)
            .call()
            .await()
            .data as? Map<String, Any?>

        val servers = (data?.get("iceServers") as? List<Map<String, Any?>>)
            ?.mapNotNull(::parseServer)
            .orEmpty()

        if (servers.isEmpty()) {
            Log.w(TAG, "issueTurnCredentials nao devolveu servidores; usando so STUN.")
            CallConfig.stunOnly()
        } else {
            CallConfig(iceServers = servers)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao obter credenciais de TURN; usando so STUN.", e)
        CallConfig.stunOnly()
    }

    private fun parseServer(raw: Map<String, Any?>): IceServerConfig? {
        val urls = when (val value = raw["urls"]) {
            is String -> listOf(value)
            is List<*> -> value.filterIsInstance<String>()
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return null

        return IceServerConfig(
            urls = urls,
            username = raw["username"] as? String,
            credential = raw["credential"] as? String,
        )
    }

    private companion object {
        const val TAG = "TurnCredentials"
        const val FUNCTION_NAME = "issueTurnCredentials"

        /** Mesma regiao sugerida na Secao 8 para o coturn self-hosted. */
        const val REGION = "southamerica-east1"
    }
}
