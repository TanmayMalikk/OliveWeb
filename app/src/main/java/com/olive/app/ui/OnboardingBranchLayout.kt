package com.olive.app.ui

import kotlin.random.Random

internal data class OnboardingSprig(val start:Float,val reach:Float,val rise:Float,val leafCount:Int,val lean:Float)

// Seeded geometry stays stable while text fields and progress state recompose.
internal class OnboardingBranchLayout(pages:Int) {
    val naturalStepOffsets=
        when(pages) {
            2 -> listOf(0f,90f)
            3 -> listOf(0f,92f,178f)
            4 -> listOf(0f,78f,154f,232f)
            else -> List(pages) { it*78f }
        }
    val sprigs=
        List(pages-1) { gap ->
            val random=Random(pages*104729+gap*7919)
            val firstSide=if(random.nextBoolean()) -1f else 1f
            List(2) { index ->
                OnboardingSprig(
                    start=(if(index==0) .18f else .48f)+random.nextFloat()*.08f,
                    reach=(if(index==0) .42f else .3f)+random.nextFloat()*.1f,
                    rise=(18f+random.nextFloat()*6f)*firstSide*(if(index==0) 1f else -1f),
                    leafCount=random.nextInt(2,4),
                    lean=random.nextFloat()*.2f-.1f,
                )
            }
        }
}
