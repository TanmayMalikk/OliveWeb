package com.olive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUploadDependencyNotifierTest {
    @Test
    fun `completed upload wakes queued messages for its owner`() {
        val wokenOwners = mutableListOf<String>()
        val notifier = MediaUploadDependencyNotifier { owner -> wokenOwners += owner }

        notifier.uploadCompleted("owner")

        assertEquals(listOf("owner"), wokenOwners)
    }

    @Test
    fun `repeated completion signals remain safe for unique message work`() {
        val wokenOwners = mutableListOf<String>()
        val notifier = MediaUploadDependencyNotifier { owner -> wokenOwners += owner }

        repeat(20) { notifier.uploadCompleted("owner") }

        assertEquals(List(20) { "owner" }, wokenOwners)
    }
}
