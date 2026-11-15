package jp.hagemi.android

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HandsFreeEntryTest {
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
    @Test fun handsFreeIsADirectEntryAndRotationIsRestoredAfterCompletionCue() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val buttons=descendants(activity.window.decorView).filterIsInstance<Button>().map{it.text.toString()}
        assertTrue(buttons.any{it.startsWith("見ずに撮る")});assertTrue(buttons.contains("画面を見ながら撮影"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity,"lockHandsFreeOrientation")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED,activity.requestedOrientation)
        ReflectionHelpers.callInstanceMethod<Unit>(activity,"showTimeline")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LOCKED,activity.requestedOrientation)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,activity.requestedOrientation)
        controller.pause().stop().destroy()
    }
}
