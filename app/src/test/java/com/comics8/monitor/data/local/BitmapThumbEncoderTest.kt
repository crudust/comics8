package com.comics8.monitor.data.local

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BitmapThumbEncoderTest {
    @Test
    fun resizesLongEdgeAndWritesJpeg() {
        val src = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.BLUE)
        val png = ByteArrayOutputStream().also {
            src.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        src.recycle()

        val jpeg = BitmapThumbEncoder.jpeg(png, longEdgePx = 200, quality = 80)
        assertThat(jpeg.size).isGreaterThan(0)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertThat(decoded).isNotNull()
        assertThat(maxOf(decoded.width, decoded.height)).isEqualTo(200)
        assertThat(minOf(decoded.width, decoded.height)).isEqualTo(100)
    }

    @Test
    fun doesNotUpscaleSmallImages() {
        val src = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        val png = ByteArrayOutputStream().also {
            src.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        src.recycle()

        val jpeg = BitmapThumbEncoder.jpeg(png, longEdgePx = 320, quality = 70)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertThat(decoded.width).isEqualTo(40)
        assertThat(decoded.height).isEqualTo(30)
    }
}
