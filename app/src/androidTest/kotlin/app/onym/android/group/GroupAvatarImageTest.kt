package app.onym.android.group

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Instrumented tests for [GroupAvatarImage]. Lives in `androidTest/`
 * because real JPEG encoding + bitmap scaling run on the device runtime
 * (Robolectric's bitmap shadows don't produce real, size-bounded JPEG
 * bytes, so the 16 KB budget can't be verified off-device).
 *
 * Mirrors `GroupAvatarImageTests.swift` from onym-ios PR #164.
 */
@RunWith(AndroidJUnit4::class)
class GroupAvatarImageTest {

    @Test
    fun encode_highEntropySource_isSquare256AndUnderBudget() {
        // Worst case for JPEG: a large, full-resolution noise image —
        // high entropy resists compression, stressing the quality loop.
        val noise = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        val rng = Random(42)
        for (y in 0 until noise.height) {
            for (x in 0 until noise.width) {
                noise.setPixel(
                    x,
                    y,
                    Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)),
                )
            }
        }

        val encoded = GroupAvatarImage.encode(noise)

        assertTrue(
            "encoded JPEG must be within the 16 KB budget, was ${encoded.size}",
            encoded.size <= GroupAvatarImage.MAX_BYTES,
        )
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals(GroupAvatarImage.SIZE, decoded.width)
        assertEquals(GroupAvatarImage.SIZE, decoded.height)
    }

    @Test
    fun encode_nonSquareSource_isCentreCroppedToSquare() {
        val wide = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        wide.eraseColor(Color.BLUE)
        val encoded = GroupAvatarImage.encode(wide)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals(GroupAvatarImage.SIZE, decoded.width)
        assertEquals(GroupAvatarImage.SIZE, decoded.height)
        assertTrue(encoded.size <= GroupAvatarImage.MAX_BYTES)
    }
}
