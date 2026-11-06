package com.alnasser.smartfleet

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("موتور اولیه سرور ساخته شده؛ اتصال و فرآیند کامل هنوز آماده نیست.").assertIsDisplayed()
        compose.onNodeWithText("متوجه شدم").performClick()
        compose.onNodeWithText("درباره").performClick()
        compose.onNodeWithText("Milad Yaghoobi").assertIsDisplayed()
    }

    @Test fun realConnectionRemainsAnExplicitChoice() {
        var opened = false
        compose.setContent { MaterialTheme { FleetPreview(onConnect = { opened = true }) } }
        compose.onNodeWithText("اتصال واقعی").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
