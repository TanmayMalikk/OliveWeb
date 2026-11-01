// Tests the permission prompt order and avoids asking users again after a choice.
package com.olive.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionsTest {
    // Inputs: the values shown below. Expected: notifications always precede the location prompt.
    @Test
    fun notificationsAlwaysPrecedeTheLocationPrompt() {
        assertEquals(
            OnboardingPermission.Notifications,
            nextOnboardingPermission(true, false, false, false, false, false, false, false)
        )
        // Either accepting or denying notifications counts as answering its prompt.
        assertEquals(
            OnboardingPermission.Location,
            nextOnboardingPermission(true, true, false, false, false, false, false, false)
        )
        assertEquals(
            OnboardingPermission.Location,
            nextOnboardingPermission(false, true, false, false, false, false, false, false)
        )
    }

    // Inputs: the values shown below. Expected: location is followed by camera and microphone.
    @Test
    fun locationIsFollowedByCameraAndMicrophone() {
        assertEquals(
            OnboardingPermission.Location,
            nextOnboardingPermission(false, false, false, false, false, false, false, false)
        )
        assertEquals(
            OnboardingPermission.Camera,
            nextOnboardingPermission(false, false, true, false, false, false, false, false)
        )
        assertEquals(
            OnboardingPermission.Microphone,
            nextOnboardingPermission(false, false, true, false, true, false, false, false)
        )
        assertEquals(
            OnboardingPermission.Done,
            nextOnboardingPermission(false, false, true, false, true, false, true, false)
        )
    }

    // Inputs: the values shown below. Expected: answered prompts do not repeat when the cover changes or app restarts.
    @Test
    fun answeredPromptsDoNotRepeatWhenTheCoverChangesOrAppRestarts() {
        assertEquals(
            OnboardingPermission.Done,
            nextOnboardingPermission(true, true, false, true, false, true, false, true)
        )
        assertEquals(
            OnboardingPermission.Notifications,
            nextOnboardingPermission(true, false, true, true, true, true, true, true)
        )
    }

    // Inputs: the values shown below. Expected: every permission state chooses the first unanswered required prompt.
    @Test
    fun everyPermissionStateChoosesTheFirstUnansweredRequiredPrompt() {
        val values = listOf(false, true)
        for (notificationsNeeded in values)
            for (notificationsAsked in values)
                for (locationGranted in values)
                    for (locationAsked in values)
                        for (cameraGranted in values)
                            for (cameraAsked in values)
                                for (microphoneGranted in values)
                                    for (microphoneAsked in values) {
                                        val expected = when {
                                            notificationsNeeded && !notificationsAsked -> OnboardingPermission.Notifications
                                            !locationGranted && !locationAsked -> OnboardingPermission.Location
                                            !cameraGranted && !cameraAsked -> OnboardingPermission.Camera
                                            !microphoneGranted && !microphoneAsked -> OnboardingPermission.Microphone
                                            else -> OnboardingPermission.Done
                                        }
                                        assertEquals(
                                            expected,
                                            nextOnboardingPermission(
                                                notificationsNeeded,
                                                notificationsAsked,
                                                locationGranted,
                                                locationAsked,
                                                cameraGranted,
                                                cameraAsked,
                                                microphoneGranted,
                                                microphoneAsked,
                                            ),
                                        )
                                    }
    }
}
