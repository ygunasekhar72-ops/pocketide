package com.pocketide.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SanityTest {

    @Test
    fun projectIdentity_isFixed() {
        assertEquals("com.pocketide.mobile", AppIdentity.APPLICATION_ID)
        assertEquals("PocketIDE", AppIdentity.APP_NAME)
    }

    @Test
    fun version_followsMilestoneScheme() {
        assertNotEquals("", AppIdentity.VERSION)
        assert(AppIdentity.VERSION.contains("M"))
    }
}
