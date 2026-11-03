// Safe contacts: shows shared user details on safe-contact cards.
package com.olive.app.ui.users.safecontact

import com.olive.app.ui.shared.*


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.UserProfile
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon


// The safe contact identity card view is part of safe contact dashboard cards. It gives the user the details and choices needed here.
@Composable
internal fun SafeContactIdentityCard(profile: UserProfile, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("safe_contact_identity"),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF0F4EC),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, OliveGreen.copy(alpha = .18f))
    ) {
        Row(
            Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = OliveGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    Text(
                        profile.fullName.trim().firstOrNull()?.uppercase() ?: "?",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.fullName.ifBlank { profile.username },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "@${profile.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                Text(
                    stringResource(R.string.safe_contact),
                    style = MaterialTheme.typography.labelLarge,
                    color = OliveGreen
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                stringResource(R.string.my_profile)
            )
        }
    }
}

// safe contact dashboard cards shows this safe contact action card view so the user can view its details and use its actions.
@Composable
internal fun SafeContactActionCard(
    label: String, description: String, icon: ImageVector, tag: String, badgeCount: Int,
    onClick: () -> Unit, modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 104.dp).testTag(tag),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF4F6EF),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, OliveGreen.copy(alpha = .22f)),
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BadgedBox(badge = { if (badgeCount > 0) Badge { Text(if (badgeCount > 99) "99+" else badgeCount.toString()) } }) {
                Surface(shape = CircleShape, color = Color(0xFFDDE7D6), contentColor = OliveGreen) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            null,
                            Modifier.size(25.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 2
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = OliveGreen)
        }
    }
}

// safe contact dashboard cards shows this safe contact profile row view so the user can view its details and use its actions.
@Composable
internal fun SafeContactProfileRow(label: String, value: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = OliveGreen)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
            Text(
                value.ifBlank { stringResource(R.string.not_provided) },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

