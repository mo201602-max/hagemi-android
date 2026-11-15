package jp.hagemi.android

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CameraQualityTest {
    @Test fun lowResolutionSearchDoesNotSetTheStillImageResolution() {
        val preview=CameraQuality.preview();val analysis=CameraQuality.analysis();val still=CameraQuality.capture()
        assertEquals(Size(640,480),preview.resolutionSelector!!.resolutionStrategy!!.boundSize)
        assertEquals(Size(640,480),analysis.resolutionSelector!!.resolutionStrategy!!.boundSize)
        assertEquals(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,analysis.backpressureStrategy)
        assertEquals(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,still.captureMode);assertEquals(100,still.jpegQuality)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY,still.resolutionSelector!!.resolutionStrategy)
        assertEquals(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE,still.resolutionSelector!!.allowedResolutionMode)
        val fallback=CameraQuality.capture(false)
        assertSame(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY,fallback.resolutionSelector!!.resolutionStrategy)
        assertEquals(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION,fallback.resolutionSelector!!.allowedResolutionMode)
    }
    @Test fun autofocusKeepsItsLockAndLeavesExposureMeteringUnchanged() {
        val point=androidx.camera.core.SurfaceOrientedMeteringPointFactory(640f,480f).createPoint(320f,240f,.1f)
        val action=CameraQuality.focusAction(point)
        assertEquals(1,action.meteringPointsAf.size)
        assertTrue(action.meteringPointsAe.isEmpty());assertTrue(action.meteringPointsAwb.isEmpty())
        assertEquals(0L,action.autoCancelDurationInMillis)
    }
}
