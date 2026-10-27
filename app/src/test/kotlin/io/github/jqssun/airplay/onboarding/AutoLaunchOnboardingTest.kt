package io.github.jqssun.airplay.onboarding

import org.junit.Assert.*
import org.junit.Test

class AutoLaunchOnboardingTest {
    @Test fun firstLaunchOffersChoiceAndDeclineShowsPathOnlyOnce() {
        val first = AutoLaunchOnboarding.restore(granted = false, handled = false, waitingForSettings = false)
        assertEquals(AutoLaunchStep.INTRO, first.step)
        assertFalse(first.handled)
        val declined = first.defer()
        assertEquals(AutoLaunchStep.LATER, declined.step)
        assertTrue(declined.handled)
        val reopened = AutoLaunchOnboarding.restore(false, declined.handled, declined.waitingForSettings)
        assertEquals(AutoLaunchStep.NONE, reopened.step)
    }

    @Test fun settingsRoundTripReportsActualGrantNotJustOpenedSettings() {
        val pending = AutoLaunchOnboarding(false, step = AutoLaunchStep.INTRO).openSettings()
        assertEquals(AutoLaunchStep.NONE, pending.step)
        assertTrue(pending.waitingForSettings)
        assertEquals(AutoLaunchStep.LATER, pending.settingsReturned(false).step)
        val granted = pending.settingsReturned(true)
        assertEquals(AutoLaunchStep.ENABLED, granted.step)
        assertFalse(granted.waitingForSettings)
        assertEquals(AutoLaunchStep.NONE, granted.dismiss().step)
    }

    @Test fun processRestartWhileInSettingsPreservesPendingDecision() {
        val pending = AutoLaunchOnboarding.restore(true, true, true, AutoLaunchStep.NONE)
        assertTrue(pending.waitingForSettings)
        assertEquals(AutoLaunchStep.ENABLED, pending.settingsReturned(true).step)
        val refused = AutoLaunchOnboarding.restore(false, true, true)
        assertEquals(AutoLaunchStep.LATER, refused.settingsReturned(false).step)
    }

    @Test fun alreadyGrantedPermissionDoesNotPromptAndRevocationDoesNotNag() {
        val permitted = AutoLaunchOnboarding.restore(true, false, false)
        assertTrue(permitted.handled)
        assertEquals(AutoLaunchStep.NONE, permitted.step)
        assertEquals(AutoLaunchStep.NONE, AutoLaunchOnboarding.restore(false, permitted.handled, false).step)
    }

    @Test fun interruptedIntroCanResumeAndDeclinePathSurvivesActivityRecreation() {
        assertEquals(AutoLaunchStep.INTRO, AutoLaunchOnboarding.restore(false, false, false, AutoLaunchStep.INTRO).step)
        assertEquals(AutoLaunchStep.LATER, AutoLaunchOnboarding.restore(false, true, false, AutoLaunchStep.LATER).step)
        assertEquals(AutoLaunchStep.NONE, AutoLaunchOnboarding.restore(true, false, false, AutoLaunchStep.INTRO).step)
    }

    @Test fun missingSettingsPageShowsManualPathWithoutRetryLoop() {
        val failed = AutoLaunchOnboarding(false).openSettings().settingsUnavailable()
        assertEquals(AutoLaunchStep.UNAVAILABLE, failed.step)
        assertFalse(failed.waitingForSettings)
        assertTrue(failed.handled)
        assertEquals(AutoLaunchStep.NONE, AutoLaunchOnboarding.restore(false, failed.handled, failed.waitingForSettings).step)
    }

    @Test fun staleSavedIntroCannotUndoARecordedRefusalOrCurrentPermission() {
        assertEquals(AutoLaunchStep.NONE, AutoLaunchOnboarding.restore(false, true, false, AutoLaunchStep.INTRO).step)
        assertEquals(AutoLaunchStep.NONE, AutoLaunchOnboarding.restore(true, true, false, AutoLaunchStep.LATER).step)
        assertEquals(AutoLaunchStep.LATER, AutoLaunchOnboarding.restore(false, true, false, AutoLaunchStep.ENABLED).step)
    }
}
