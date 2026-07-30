@file:Suppress("UNUSED_PARAMETER", "unused")
package org.tensorflow.lite
class Interpreter(buffer: java.nio.ByteBuffer, options: Options) : java.io.Closeable {
    class Options {
        fun setNumThreads(n: Int): Options = this
        fun setUseXNNPACK(b: Boolean): Options = this
        fun setUseNNAPI(b: Boolean): Options = this
    }
    fun run(input: Any, output: Any) {}
    fun allocateTensors() {}
    override fun close() {}
}
