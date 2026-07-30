@file:Suppress("UNUSED_PARAMETER", "unused")
package android.content.res
class AssetFileDescriptor {
    fun getFileDescriptor(): java.io.FileDescriptor = java.io.FileDescriptor()
    fun getStartOffset(): Long = 0
    fun getDeclaredLength(): Long = 0
    fun close() {}
}
class AssetManager { fun openFd(name: String): AssetFileDescriptor = AssetFileDescriptor() }
