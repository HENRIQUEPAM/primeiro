@file:Suppress("UNUSED_PARAMETER", "unused")
package android.graphics

class Rect(@JvmField var left: Int, @JvmField var top: Int, @JvmField var right: Int, @JvmField var bottom: Int) {
    constructor() : this(0, 0, 0, 0)
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}
class PointF(@JvmField var x: Float, @JvmField var y: Float)
class Matrix {
    fun setValues(values: FloatArray) {}
    fun postRotate(deg: Float) {}
    fun postScale(sx: Float, sy: Float) {}
    fun postTranslate(dx: Float, dy: Float) {}
}
class Paint(flags: Int) {
    constructor() : this(0)
    var isFilterBitmap: Boolean = false
    var isAntiAlias: Boolean = false
    var isDither: Boolean = false
    companion object { const val FILTER_BITMAP_FLAG = 2 }
}
class Canvas(bitmap: Bitmap) {
    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {}
    fun drawColor(color: Int) {}
    fun setBitmap(bitmap: Bitmap?) {}
}
object Color { const val BLACK: Int = 0 }
class Bitmap {
    enum class Config { ARGB_8888, RGB_565 }
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {}
    fun recycle() {}
    fun isRecycled(): Boolean = false
    val width: Int get() = 0
    val height: Int get() = 0
    val config: Config? get() = null
    companion object {
        fun createBitmap(w: Int, h: Int, config: Config): Bitmap = Bitmap()
        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap = Bitmap()
        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?, filter: Boolean): Bitmap = Bitmap()
    }
}
class BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds: Boolean = false
        @JvmField var inSampleSize: Int = 1
        @JvmField var outWidth: Int = 0
        @JvmField var outHeight: Int = 0
        @JvmField var inPreferredConfig: Bitmap.Config? = null
    }
    companion object {
        @JvmStatic fun decodeFile(path: String, opts: Options?): Bitmap? = null
        @JvmStatic fun decodeFile(path: String): Bitmap? = null
    }
}
