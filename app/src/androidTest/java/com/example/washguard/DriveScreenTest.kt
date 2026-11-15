package com.example.washguard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DriveScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedHomeActionNavigates() {
        var route = "DRIVE"
        compose.setContent { MaterialTheme {
            DriveScreen(DemoDriveRepository().session.value, DriveAccess.PARKED, null, {}, {}, { route = it })
        } }
        compose.onNodeWithText("HOME").performClick()
        compose.runOnIdle { assertEquals("HOME", route) }
    }

    @Test fun restrictedModeDisablesNavigation() {
        compose.setContent { MaterialTheme {
            DriveScreen(DemoDriveRepository().session.value, DriveAccess.RESTRICTED, null, {}, {})
        } }
        compose.onNodeWithText("HOME").assertIsNotEnabled()
        compose.onNodeWithText("DRIVE REPORT  /  AFTER PARKING").assertIsNotEnabled()
    }
}
