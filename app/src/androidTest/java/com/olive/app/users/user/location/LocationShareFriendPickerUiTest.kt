package com.olive.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.data.messaging.Friend
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.location.LocationShareFriendPicker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationShareFriendPickerUiTest {
    @get:Rule val compose = createOliveComposeRule()

    @Test
    fun searchesOnlyAddedFriendsAndRetainsMoreThanTenSelections() {
        val friends = (1..12).map { Friend("friendship-$it", "uid-$it", "friend_${it.toString().padStart(2, '0')}") }
        compose.activity.setContent {
            OliveTheme {
                var selected by remember { mutableStateOf(emptySet<String>()) }
                Column {
                    LocationShareFriendPicker(
                        accountId = "test",
                        friends = friends,
                        contactNames = mapOf("uid-12" to "Trusted name"),
                        selected = selected,
                        enabled = true,
                        onSelectionChange = { uid, checked ->
                            selected = if (checked) selected + uid else selected - uid
                        },
                    )
                    Text(selected.size.toString(), Modifier.testTag("selected_count"))
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("location_friend_list").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        val list = compose.onNodeWithTag("location_friend_list")
        compose.onNodeWithTag("location_friend_uid-1").assertIsDisplayed()
        compose.onNodeWithTag("location_friend_uid-3").assertIsDisplayed()
        compose.onNodeWithTag("location_friend_uid-4").assertIsNotDisplayed()
        for (index in 1..11) {
            val tag = "location_friend_uid-$index"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick().assertIsOn()
        }
        val search = compose.onNodeWithTag("location_friend_search")
        search.performTextInput("trusted name")
        compose.onNodeWithTag("location_friend_uid-12").performClick().assertIsOn()
        compose.onNodeWithTag("selected_count").assertTextEquals("12")
        search.performTextReplacement("@friend_01")
        compose.onNodeWithTag("location_friend_uid-1").assertIsOn()
        search.performTextReplacement("unknown")
        compose.onNodeWithTag("location_friend_list").assertDoesNotExist()
    }
}
