package com.olive.app.ui

import org.junit.Assert.*
import org.junit.Test

class OnboardingBranchLayoutTest {
    @Test fun everySupportedFlowHasOrderedStepsAndStableLeaves() {
        for(pages in 2..4) {
            val first=OnboardingBranchLayout(pages)
            val second=OnboardingBranchLayout(pages)
            assertEquals(pages,first.naturalStepOffsets.size)
            assertTrue(first.naturalStepOffsets.zipWithNext().all { (a,b) -> b>a })
            assertEquals(pages-1,first.sprigs.size)
            assertEquals(first.sprigs,second.sprigs)
            assertTrue(first.sprigs.flatten().all { it.leafCount>0 && it.reach>0f })
        }
    }
}
