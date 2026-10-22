package com.olive.app.ui

import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType

internal fun AccountRole.localizedTitle(text:ResourceText)=text(when(this) {
    AccountRole.USER -> R.string.user
    AccountRole.SAFE_CONTACT -> R.string.safe_contact
    AccountRole.STAFF -> R.string.staff
    AccountRole.ADMIN -> R.string.admin
})

internal fun AccountRole.localizedDescription(text:ResourceText)=text(when(this) {
    AccountRole.USER -> R.string.the_person_using_the_private_cover_and_safe_app
    AccountRole.SAFE_CONTACT -> R.string.a_trusted_person_helping_someone
    AccountRole.STAFF -> R.string.an_organization_staff_member
    AccountRole.ADMIN -> R.string.an_organization_leader_who_oversees_staff
})

internal fun CoverType.localizedTitle(text:ResourceText)=text(when(this) {
    CoverType.FOOD -> R.string.ui_olive_8f62f4
    CoverType.NEWS -> R.string.ui_daily_digest
    CoverType.CALCULATOR -> R.string.better_calculator
    CoverType.GAME -> R.string.ui_game
})
