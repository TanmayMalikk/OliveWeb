package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.olive.app.R
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.shared.SkeletonLayout
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class LoadingSkeletonUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun placeholdersPresentOneLoadingAnnouncementWithoutSampleTextOrActions() {
        val selected = mutableStateOf(SkeletonLayout.Conversation)
        compose.setContent {
            OliveTheme {
                Box(Modifier.fillMaxSize()) { LoadingSkeleton(selected.value) }
            }
        }
        val label = compose.activity.getString(R.string.ui_loading)
        val skeleton = compose.onNodeWithTag("loading_skeleton")
        val heights = SkeletonLayout.entries.associateWith { layout ->
            compose.runOnIdle { selected.value = layout }
            skeleton.assertIsDisplayed().assertContentDescriptionEquals(label)
            compose.onAllNodes(hasContentDescription(label)).assertCountEquals(1)
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("loading_skeleton"))).assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
            skeleton.fetchSemanticsNode().size.height
        }
        assertNotEquals(heights[SkeletonLayout.Image], heights[SkeletonLayout.Video])
        assertNotEquals(heights[SkeletonLayout.FitnessVitals], heights[SkeletonLayout.Form])
    }
}
