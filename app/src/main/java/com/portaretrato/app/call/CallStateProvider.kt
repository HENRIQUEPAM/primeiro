package com.portaretrato.app.call

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Publica `call_in_progress` para o watchdog/MCU via Content Provider local
 * somente-leitura, conforme as Secoes 6 e 7.5 da especificacao v3.1.
 *
 * ```
 * content://com.portaretrato.callstate/status  ->  IDLE | RINGING | IN_CALL
 * ```
 *
 * **Fonte unica.** O mesmo `CallState` que gateia o WorkManager alimenta este
 * provider. A especificacao insiste nisso: um unico sinal, duas leituras — o
 * firmware e o agendamento da varredura facial nunca podem divergir sobre se
 * ha chamada em andamento.
 *
 * Somente-leitura de verdade: `insert`, `update` e `delete` lancam. Um processo
 * externo alterar o estado de chamada seria uma superficie de ataque sem
 * nenhuma utilidade.
 */
class CallStateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment != PATH_STATUS) return null
        return MatrixCursor(arrayOf(COLUMN_STATUS)).apply {
            addRow(arrayOf(currentStatus()))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.status"

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Somente leitura")

    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int =
        throw UnsupportedOperationException("Somente leitura")

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int =
        throw UnsupportedOperationException("Somente leitura")

    private fun currentStatus(): String =
        when (CallService.activeController?.uiState?.value?.state) {
            null, CallState.IDLE, CallState.ENDED -> STATUS_IDLE
            CallState.RINGING, CallState.DIALING -> STATUS_RINGING
            CallState.CONNECTING, CallState.ACTIVE, CallState.RECONNECTING -> STATUS_IN_CALL
        }

    companion object {
        const val AUTHORITY = "com.portaretrato.callstate"
        const val PATH_STATUS = "status"
        const val COLUMN_STATUS = "status"

        const val STATUS_IDLE = "IDLE"
        const val STATUS_RINGING = "RINGING"
        const val STATUS_IN_CALL = "IN_CALL"

        val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_STATUS")
    }
}
