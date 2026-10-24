package dev.luma.agent

import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ScreenTapTargetTest {
    @Test fun aWebLinkUsesOnePhysicalTapEvenWhenItAdvertisesAnAccessibilityClick() {
        val web=AccessibilityNodeInfo.obtain().apply {className="android.webkit.WebView";isClickable=true}
        val link=AccessibilityNodeInfo.obtain().apply {className="android.view.View";isClickable=true;text="Better Voxels"}
        shadowOf(web).addChild(link)
        assertTrue(ScreenTapTarget.needsGesture(link))
        assertTrue(shadowOf(web).performedActions.isEmpty());assertTrue(shadowOf(link).performedActions.isEmpty())
    }
    @Test fun aNativeLabelDoesNotRedirectTheTapToItsClickableParent() {
        val parent=AccessibilityNodeInfo.obtain().apply {className="android.widget.LinearLayout";isClickable=true}
        val label=AccessibilityNodeInfo.obtain().apply {className="android.widget.TextView";isClickable=false;text="Нужная ссылка"}
        shadowOf(parent).addChild(label)
        assertTrue(ScreenTapTarget.needsGesture(label));assertFalse(ScreenTapTarget.needsGesture(parent))
        assertTrue(shadowOf(parent).performedActions.isEmpty())
    }
}
