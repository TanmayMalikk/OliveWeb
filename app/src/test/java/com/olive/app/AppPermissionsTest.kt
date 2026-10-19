package com.olive.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionsTest {
    @Test fun notificationsAlwaysPrecedeTheLocationPrompt() {
        assertEquals(OnboardingPermission.Notifications,nextOnboardingPermission(true,false,false,false))
        // Either accepting or denying notifications counts as answering its prompt.
        assertEquals(OnboardingPermission.Location,nextOnboardingPermission(true,true,false,false))
        assertEquals(OnboardingPermission.Location,nextOnboardingPermission(false,true,false,false))
    }
    @Test fun existingNotificationPermissionOrOlderAndroidProceedsToLocation() {
        assertEquals(OnboardingPermission.Location,nextOnboardingPermission(false,false,false,false))
        assertEquals(OnboardingPermission.Done,nextOnboardingPermission(false,false,true,false))
    }
    @Test fun answeredPromptsDoNotRepeatWhenTheCoverChangesOrAppRestarts() {
        assertEquals(OnboardingPermission.Done,nextOnboardingPermission(true,true,false,true))
        assertEquals(OnboardingPermission.Notifications,nextOnboardingPermission(true,false,true,true))
    }
}
