package com.alnasser.smartfleet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FleetPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun introCreditsCreator() {
        compose.setContent { MaterialTheme { BrandIntro(onContinue = {}) } }
        compose.onNodeWithText("Milad Yaghoobi").assertIsDisplayed()
        compose.onNodeWithText("ورود به معرفی").assertIsDisplayed()
    }

    @Test fun previewModulesExplainTheirRealStatus() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { FleetPreview(onConnect = {}) }
            }
        }
        compose.onNodeWithText("امکانات").performClick()
        compose.onNodeWithText("مدیریت سوخت").performClick()
        compose.onNodeWithText("سوخت و سهمیه آزمایشی").assertIsDisplayed()
        compose.onNodeWithText("بازگشت به امکانات").performClick()
        compose.onNodeWithText("درباره").performClick()
        compose.onNodeWithText("Milad Yaghoobi").assertIsDisplayed()
    }

    @Test fun realConnectionRemainsAnExplicitChoice() {
        var opened = false
        compose.setContent { MaterialTheme { FleetPreview(onConnect = { opened = true }) } }
        compose.onNodeWithText("اتصال واقعی").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
    @Test fun customerCanRegisterAVehicleInIsolatedDemo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "demo-preview-ui-test"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val store = DemoStore(context, name)
        try {
            compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { DemoModule("FLEET", store) {} } } }
            compose.onNodeWithText("پلاک خودرو").performTextInput("123")
            compose.onNodeWithText("مدل خودرو").performTextInput("Isuzu")
            compose.onNodeWithText("ثبت خودروی آزمایشی").performClick()
            compose.runOnIdle { assertTrue(store.list("vehicles").any { it.getString("plate") == "123" }) }
        } finally { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

}
