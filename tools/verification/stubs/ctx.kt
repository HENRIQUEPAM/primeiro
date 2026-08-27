@file:Suppress("UNUSED_PARAMETER", "unused")
package android.content

import java.io.File

/**
 * Stub minimo de Context.
 *
 * `filesDir` existe aqui para que PhotoLibrary, FaceDatabaseStore e
 * FaceScanCoordinator possam ser TIPADOS na JVM. Eles nao sao exercitados por
 * teste (dependem de disco e do ML Kit de verdade), mas type-check ja pega a
 * classe de erro que mais quebrou builds neste projeto: assinatura trocada,
 * import faltando, nome de metodo errado.
 */
class Context {
    fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
    val filesDir: File get() = File(System.getProperty("java.io.tmpdir"), "stub-files")
    val contentResolver: ContentResolver get() = ContentResolver()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences()

    companion object { const val MODE_PRIVATE = 0 }
}

class SharedPreferences {
    fun getString(key: String, default: String?): String? = default
    fun getLong(key: String, default: Long): Long = default
    fun getInt(key: String, default: Int): Int = default
    fun getBoolean(key: String, default: Boolean): Boolean = default
    fun edit(): Editor = Editor()

    class Editor {
        fun putString(key: String, value: String?): Editor = this
        fun putLong(key: String, value: Long): Editor = this
        fun putInt(key: String, value: Int): Editor = this
        fun putBoolean(key: String, value: Boolean): Editor = this
        fun remove(key: String): Editor = this
        fun clear(): Editor = this
        fun apply() {}
        fun commit(): Boolean = true
    }
}

class ContentResolver {
    fun openInputStream(uri: android.net.Uri): java.io.InputStream? = null
}
