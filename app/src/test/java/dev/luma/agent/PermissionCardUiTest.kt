package dev.luma.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.luma.agent.core.*
import dev.luma.agent.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionCardUiTest {
    @org.junit.Before fun locale() {I18n.choose("ru")}
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun alternativeIsVisibleAndInvokesItsOwnAction() {
        var fallback = false; var grant = false
        compose.setContent { LumaTheme(AppSettings(theme = "dark", dynamicColors = false, reducedMotion = true)) { Surface {
            RunCard(RunState(active = true, acting = true, permission = PermissionNeed("phone", "Телефон", "Звонок", fallback = "Открыть звонилку")), {}, {}, {}, {},
                grantAccess = { grant = true }, fallbackAccess = { fallback = true })
        } } }
        compose.onNodeWithTag("grant-access").assertIsDisplayed().performClick()
        assertTrue(grant)
        compose.onNodeWithTag("continue-without-access").assertIsDisplayed().performClick()
        assertTrue(fallback)
    }
    @Test fun noAlternativeIsOfferedForReadingPrivateData() {
        var skipped = false
        compose.setContent { LumaTheme(AppSettings(theme = "light", dynamicColors = false, reducedMotion = true)) { Surface {
            RunCard(RunState(active = true, acting = true, permission = PermissionNeed("sms", "SMS", "Чтение SMS")), {}, {}, {}, {}, skipAccess = { skipped = true })
        } } }
        compose.onNodeWithTag("grant-access").assertIsDisplayed()
        compose.onNodeWithTag("continue-without-access").assertDoesNotExist()
        compose.onNodeWithText("Пропустить").performClick(); assertTrue(skipped)
    }
}
