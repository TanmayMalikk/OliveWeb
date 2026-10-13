package com.olive.app.ui

import com.olive.app.ui.covers.olive.AddressFields

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.time.LocalDate
import java.time.Month
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

private data class OnboardingSprig(val start:Float,val reach:Float,val rise:Float,val leafCount:Int,val lean:Float)

// Numbered olives hang from the branch and mark each onboarding page.
@Composable internal fun OnboardingProgress(completed:Int,total:Int,page:Int,pages:Int,pageComplete:List<Boolean>) {
    if(pages<=1) return
    // Keep each flow's leaf sizes independent of the screen width.
    val naturalStepOffsets=remember(pages) {
        when(pages) {
            2 -> listOf(0f,90f)
            3 -> listOf(0f,92f,178f)
            4 -> listOf(0f,78f,154f,232f)
            else -> List(pages) { it*78f }
        }
    }
    // Seed each gap separately so leaves vary, but stay put across typing and page changes.
    val sprigs=remember(pages) {
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
    Column(verticalArrangement=Arrangement.spacedBy(2.dp),modifier=Modifier.fillMaxWidth().testTag("onboarding_progress")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Stretch only the stem and distribute fruit along it; artwork stays at its original size.
            val availableSpan=(maxWidth.value-72f).coerceAtLeast(0f)
            val stepOffsets=naturalStepOffsets.map { it/naturalStepOffsets.last()*availableSpan }
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset=24.dp.toPx()
                    val middle=size.height/2
                    drawLine(OliveBrown.copy(alpha=.7f),Offset(5.dp.toPx(),middle),
                        Offset(inset,middle),2.dp.toPx(),StrokeCap.Round)
                    repeat(pages-1) { index ->
                        val start=inset+stepOffsets[index].dp.toPx()
                        val spacing=(stepOffsets[index+1]-stepOffsets[index]).dp.toPx()
                        val end=start+spacing
                        val grown=index<page
                        val stemColor=if(grown) OliveBrown else OliveBrown.copy(alpha=.7f)
                        val leafColor=if(grown) MossGreen else SageGreen
                        val stem=Path().apply {
                            moveTo(start,middle)
                            cubicTo(start+spacing*.3f,middle-7.dp.toPx(),
                                start+spacing*.7f,middle+7.dp.toPx(),end,middle)
                        }
                        drawPath(stem,stemColor,style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
                        sprigs[index].forEach { sprig ->
                            val fraction=sprig.start
                            val bend=3*fraction*(1-fraction)*(1-2*fraction)*(-7.dp.toPx())
                            drawOnboardingSprig(Offset(start+spacing*fraction,middle+bend),
                                (naturalStepOffsets[index+1]-naturalStepOffsets[index]).dp.toPx(),sprig,stemColor,leafColor)
                        }
                    }
                    val tipStart=inset+stepOffsets.last().dp.toPx()
                    val tip=Path().apply {
                        moveTo(tipStart,middle)
                        cubicTo(tipStart+12.dp.toPx(),middle+2.dp.toPx(),
                            size.width-12.dp.toPx(),middle-6.dp.toPx(),size.width-3.dp.toPx(),middle-12.dp.toPx())
                    }
                    drawPath(tip,OliveBrown.copy(alpha=.7f),style=Stroke(1.3.dp.toPx(),cap=StrokeCap.Round))
                    drawOnboardingLeaf(Offset(size.width-20.dp.toPx(),middle-4.dp.toPx()),
                        Offset(size.width-5.dp.toPx(),middle-26.dp.toPx()),SageGreen)
                    // Short curved stalks attach each numbered olive to the main branch.
                    repeat(pages) { index ->
                        val center=inset+stepOffsets[index].dp.toPx()
                        val side=if(index%2==0) 1f else -1f
                        val stalk=Path().apply {
                            moveTo(center+10.dp.toPx(),middle)
                            cubicTo(center+4.dp.toPx(),middle+side*5.dp.toPx(),
                                center-6.dp.toPx(),middle+side*10.dp.toPx(),
                                center-6.dp.toPx(),middle+side*16.dp.toPx())
                        }
                        drawPath(stalk,OliveBrown,style=Stroke(1.5.dp.toPx(),cap=StrokeCap.Round))
                    }
                }
                repeat(pages) { index ->
                    val done=index<page || (index==page && pageComplete.getOrElse(index) { false })
                    val active=index==page
                    val oliveColor by animateColorAsState(if(done) OliveGreen else PaleOlive,tween(300),label="step_fill_$index")
                    val borderColor by animateColorAsState(if(done || active) OliveGreen else SoftGray,tween(300),label="step_border_$index")
                    val numberColor by animateColorAsState(if(done) Paper else if(active) OliveGreen else Muted,tween(300),label="step_number_$index")
                    val oliveWidth by animateDpAsState(if(active) 34.dp else 30.dp,tween(250),label="step_width_$index")
                    val oliveHeight by animateDpAsState(if(active) 44.dp else 40.dp,tween(250),label="step_height_$index")
                    val borderWidth by animateDpAsState(if(active) 2.dp else 1.dp,tween(250),label="step_border_width_$index")
                    val oliveOffset=if(index%2==0) 24.dp else (-24).dp
                    val oliveAngle=if(index%2==0) -18f else 18f
                    Box(Modifier.offset(x=stepOffsets[index].dp,y=oliveOffset).width(48.dp).fillMaxHeight(),contentAlignment=Alignment.Center) {
                        Surface(shape=RoundedCornerShape(50),color=oliveColor,
                            border=BorderStroke(borderWidth,borderColor),
                            modifier=Modifier.size(oliveWidth,oliveHeight).rotate(oliveAngle).testTag("onboarding_step_${index+1}")) {
                            Box(Modifier.rotate(-oliveAngle),contentAlignment=Alignment.Center) {
                                LocalizedText("${index+1}",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color=numberColor)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            LocalizedText("Step ${page+1} of $pages",style=MaterialTheme.typography.labelLarge)
            LocalizedText("$completed of $total required",style=MaterialTheme.typography.labelMedium,color=Muted)
        }
    }
}

// Offshoots and leaf tips lean right, giving the branch a clear direction of growth.
private fun DrawScope.drawOnboardingSprig(base:Offset,spacing:Float,sprig:OnboardingSprig,stemColor:Color,leafColor:Color) {
    val reach=spacing*sprig.reach
    val rise=sprig.rise.dp.toPx()
    fun point(x:Float,y:Float)=base+Offset(reach*x,rise*y)
    val tip=point(1f,1f)
    val sprigPath=Path().apply {
        moveTo(base.x,base.y)
        val first=point(.38f,.08f)
        val second=point(.55f,1.05f)
        cubicTo(first.x,first.y,second.x,second.y,tip.x,tip.y)
    }
    drawPath(sprigPath,stemColor,style=Stroke(1.dp.toPx(),cap=StrokeCap.Round))
    drawOnboardingLeaf(point(.3f,.42f),point(.55f+sprig.lean,1.3f),leafColor)
    drawOnboardingLeaf(point(.58f,.83f),point(1.03f,.96f+sprig.lean),leafColor)
    if(sprig.leafCount==3) drawOnboardingLeaf(point(.74f,.95f),point(.94f+sprig.lean,.35f),leafColor)
}

// Two curved edges form a narrow olive leaf; its vein points back to the stem.
private fun DrawScope.drawOnboardingLeaf(base:Offset,tip:Offset,color:Color) {
    val direction=tip-base
    val side=Offset(-direction.y,direction.x)/direction.getDistance()*4.dp.toPx()
    val leaf=Path().apply {
        moveTo(base.x,base.y)
        val first=base+direction*.35f+side
        val second=base+direction*.75f+side
        cubicTo(first.x,first.y,second.x,second.y,tip.x,tip.y)
        val third=base+direction*.75f-side
        val fourth=base+direction*.35f-side
        cubicTo(third.x,third.y,fourth.x,fourth.y,base.x,base.y)
        close()
    }
    drawPath(leaf,color)
    drawLine(OliveGreen.copy(alpha=.35f),base,base+direction*.8f,.6.dp.toPx(),StrokeCap.Round)
}

// Requirement bullets begin neutral, then turn green or red as the user types.
@Composable internal fun RequirementBullet(text:String,met:Boolean,active:Boolean) {
    val color=when { !active -> Color.Black;met -> OliveGreen;else -> MaterialTheme.colorScheme.error }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        LocalizedText("•",color=color,fontSize=18.sp)
        LocalizedText(text,color=color,style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible=active && met,enter=fadeIn(tween(150)),exit=fadeOut(tween(100))) {
            Icon(Icons.Outlined.CheckCircle,contentDescription="Requirement met",tint=OliveGreen,modifier=Modifier.size(18.dp))
        }
    }
}

// Every onboarding page ends with the same Back and Next arrow controls.
@Composable internal fun OnboardingNavigation(back:()->Unit,next:()->Unit,last:Boolean=false,nextTag:String="onboarding_next",
    enabled:Boolean=true,nextLabel:String?=null) {
    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick=back,enabled=enabled,modifier=Modifier.weight(1f).testTag("onboarding_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(8.dp));LocalizedText("Back")
        }
        Button(onClick=next,enabled=enabled,modifier=Modifier.weight(1f).testTag(nextTag)) {
            LocalizedText(nextLabel ?: if(last) "Finish" else "Next");Spacer(Modifier.width(8.dp));Icon(Icons.AutoMirrored.Outlined.ArrowForward,null)
        }
    }
}

// ALL ACCOUNT TYPES: role selection routes each person into the correct onboarding path.
// USER and SAFE CONTACT can create accounts here; STAFF and ADMIN use administrator-issued logins.
// UI sizing/text: the role menu inherits FormColumn spacing; descriptions intentionally use bodySmall and Muted so long translations can wrap.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Welcome(session: SessionViewModel) {
    var roleOpen by remember { mutableStateOf(false) }
    FormColumn {
        Heading("Welcome", "First, choose what kind of account you use.",if(session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)) "Olive Tree" else "Olive Branch")
        ModeNotice(session)
        ExposedDropdownMenuBox(expanded=roleOpen,onExpandedChange={roleOpen=!roleOpen}) {
            OutlinedTextField(value=session.accountRole.title,onValueChange={},readOnly=true,label={LocalizedText("Account type")},
                trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(roleOpen)},modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("account_role"))
            ExposedDropdownMenu(expanded=roleOpen,onDismissRequest={roleOpen=false}) {
                AccountRole.entries.forEach { role -> DropdownMenuItem(
                    text={Column { LocalizedText(role.title);LocalizedText(role.description,style=MaterialTheme.typography.bodySmall,color=Muted) }},
                    onClick={session.selectRole(role);roleOpen=false},modifier=Modifier.testTag("role_${role.name.lowercase()}")) }
            }
        }
        if(session.accountRole==AccountRole.USER || session.accountRole==AccountRole.SAFE_CONTACT) {
            Button(onClick=session::chooseNew,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("new_user")) { LocalizedText("Create a new account") }
            OutlinedButton(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in to an existing account") }
        } else {
            LocalizedText("${session.accountRole.title} accounts are created by an administrator. Sign in with your username or the real email saved to your account.",color=Muted)
            Button(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in") }
        }
        Status(session)
    }
}

// ALL ACCOUNT TYPES: username/password sign-in. New-account creation is available only to USER and SAFE CONTACT.
// UI sizing/text: credential fields use the shared full-width field size; password guidance uses bodySmall and Muted.
@Composable internal fun Credentials(session: SessionViewModel, create: Boolean) {
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val organizationLogin=!create && session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)
    val usernameValid=if(organizationLogin && '@' in username) android.util.Patterns.EMAIL_ADDRESS.matcher(username.trim()).matches()
        else if(create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(username)
    val passwordValid=if(create) InputPolicy.validPassword(password) else password.isNotEmpty()
    val confirmationValid=!create || (confirm.isNotEmpty() && confirm==password)
    val completed=listOf(usernameValid,passwordValid,confirmationValid).take(if(create) 3 else 2).count { it }
    val stages=when {
        create && session.accountRole==AccountRole.USER -> 4
        create && session.accountRole==AccountRole.SAFE_CONTACT -> 3
        !create && session.accountRole==AccountRole.USER -> 2
        else -> 1
    }
    val totalRequired=when {
        create && session.accountRole==AccountRole.USER -> 10
        create && session.accountRole==AccountRole.SAFE_CONTACT -> 7
        !create && session.accountRole==AccountRole.USER -> 4
        else -> 2
    }
    val usernameError=when {
        session.credentialFieldError==CredentialFieldError.UsernameTaken -> "Username taken"
        submitted && !usernameValid -> "Invalid username"
        else -> null
    }
    val passwordError=when {
        session.credentialFieldError==CredentialFieldError.IncorrectPassword -> "Incorrect password"
        submitted && !passwordValid -> if(create) "Invalid password" else "Password required"
        else -> null
    }
    val confirmError=if(create && submitted && !confirmationValid) "Passwords differ" else null
    FormColumn {
        Heading(if(create) "Create your ${session.accountRole.title} account" else "${session.accountRole.title} sign in",
            if(create) "Use these credentials when setting up another device."
            else when(session.accountRole) {
                AccountRole.USER -> "Sign in with your existing username and password. Next, create a PIN for this device."
                AccountRole.SAFE_CONTACT -> "Sign in with your existing username and password."
                else -> "Use the credentials issued by an administrator."
            },if(session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)) "Olive Tree" else "Olive Branch")
        ModeNotice(session)
        if(stages>1) OnboardingProgress(completed,totalRequired,0,stages,
            List(stages) { index -> index==0 && usernameValid && passwordValid && confirmationValid })
        Field(if(organizationLogin) "Username or email" else "Username",username,"username",enabled=!session.busy,error=usernameError) {
            username=it.take(if(create) 24 else 254);submitted=false;session.clearCredentialError()
        }
        if(create) {
            // UI spacing: keep related validation bullets close together below their input field.
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                RequirementBullet("8–24 characters",username.length in 8..24,username.isNotEmpty())
                RequirementBullet("Letters, numbers or _",username.matches(Regex("[A-Za-z0-9_]+")),username.isNotEmpty())
            }
        }
        Field("Password",password,"password",secret=true,showSecretToggle=true,enabled=!session.busy,error=passwordError) {
            password=it.take(if(create) 24 else 128);submitted=false;session.clearCredentialError()
        }
        if(create) {
            // UI spacing: password requirements read as one compact list.
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                RequirementBullet("8–24 characters",InputPolicy.passwordHasValidLength(password),password.isNotEmpty())
                RequirementBullet("At least 1 letter",InputPolicy.passwordHasLetter(password),password.isNotEmpty())
                RequirementBullet("At least 1 number",InputPolicy.passwordHasNumber(password),password.isNotEmpty())
                RequirementBullet("At least 1 special character",InputPolicy.passwordHasSpecialCharacter(password),password.isNotEmpty())
            }
            Field("Confirm password",confirm,"confirm_password",secret=true,showSecretToggle=true,enabled=!session.busy,error=confirmError) {
                confirm=it.take(24);submitted=false;session.clearCredentialError()
            }
            RequirementBullet("Passwords match",confirmationValid,password.isNotEmpty() || confirm.isNotEmpty())
        }
        Status(session,showMessage=session.credentialFieldError==null)
        OnboardingNavigation(session::back,{
            submitted=true
            if(usernameValid && passwordValid && confirmationValid) session.authenticate(username,password,create)
        },nextTag="credentials_submit",enabled=!session.busy,
            nextLabel=if(create) "Create account" else "Sign in")
    }
}

// USER and SAFE CONTACT: birthday is required and saved as YYYY-MM-DD.
// UI sizing: the three birthday buttons share one row equally with 8dp gaps and 8dp horizontal button padding.
@Composable internal fun BirthdayPicker(value: String, tag: String, enabled: Boolean,error:String?=null,onChange: (String)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val initial=remember { runCatching { LocalDate.parse(value) }.getOrNull() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    var day by remember { mutableStateOf(initial?.dayOfMonth) }
    val locale= Locale.forLanguageTag(getLanguageTag(LocalOliveLanguage.current))
    fun setDate(y: Int?,m: Int?,d: Int?) {
        if(y!=null && m!=null && d!=null) onChange("%04d-%02d-%02d".format(Locale.ROOT,y,m,d))
    }
    LocalizedText("Date of birth (required)",style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().testTag(tag),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun Choice(label: String, selected: String, options: List<Pair<String,String>>, choiceTag: String, choose: (String)->Unit) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,
                    border=if(error!=null) BorderStroke(1.dp,MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonBorder(enabled),
                    modifier=Modifier.fillMaxWidth().testTag(choiceTag),contentPadding=PaddingValues(horizontal=8.dp)) {
                    LocalizedText(selected.ifBlank { label },maxLines=1)
                }
                DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                    options.forEach { (id,text) ->
                        DropdownMenuItem(text={LocalizedText(text)},onClick={choose(id);open=false},modifier=Modifier.testTag("${choiceTag}_$id"))
                    }
                }
            }
        }
        Choice("Year",year?.toString().orEmpty(),(1950..LocalDate.now().year).reversed().map { it.toString() to it.toString() },"${tag}_year") {
            year=it.toInt();setDate(year,month,day)
        }
        Choice("Month",month?.let { Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) }.orEmpty(),
            (1..12).map { it.toString() to Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) },"${tag}_month") {
            month=it.toInt();setDate(year,month,day)
        }
        Choice("Day",day?.toString().orEmpty(),(1..31).map { it.toString() to it.toString() },"${tag}_day") {
            day=it.toInt();setDate(year,month,day)
        }
    }
    error?.let { LocalizedText(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
}

// USER and SAFE CONTACT: shared profile questions live here.
// USER ONLY: abuse details, situation, trigger, optional safe contact, and cover-app choice are inside !safeContact blocks.
// UI sizing/text: section titles use Material title sizes, helper text uses bodySmall, and all fields use the shared responsive form width.
@Composable internal fun ProfileSetup(session: SessionViewModel) {
    val safeContact=session.accountRole==AccountRole.SAFE_CONTACT
    val existing=session.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var affected by remember(existing) { mutableStateOf(existing?.affectedNames.orEmpty()) }
    var abuserName by remember(existing) { mutableStateOf(existing?.abuserName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var language by remember(existing) { mutableStateOf(existing?.language?.ifBlank { "English" } ?: "English") }
    var situation by remember(existing) { mutableStateOf(existing?.situation.orEmpty()) }
    var address by remember(existing) { mutableStateOf(existing?.address ?: DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    val contacts=remember(existing) { mutableStateListOf<SafeContact>().also { it.addAll(existing?.contacts.orEmpty()) } }
    var coverType by remember(existing) { mutableStateOf(existing?.coverType ?: CoverType.FOOD) }
    val pages=if(safeContact) listOf("Personal details","Location") else
        listOf("Personal details","Location","Safe entry & cover")
    var page by rememberSaveable(existing?.username,safeContact) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val required=listOf(fullName.isNotBlank(),InputPolicy.validDob(dob),language.isNotBlank(),address.locationValid)+
        if(safeContact) emptyList() else listOf(InputPolicy.validTrigger(trigger))
    val completed=required.count { it }
    val personalValid=fullName.isNotBlank() && InputPolicy.validDob(dob) && language.isNotBlank()
    val supportValid=contactName.isBlank()==contactDetails.isBlank()
    val localCompletion=if(safeContact) listOf(personalValid,address.locationValid) else
        listOf(personalValid,address.locationValid,InputPolicy.validTrigger(trigger) && supportValid)
    val stages=if(safeContact) 3 else 4
    val progressPage=page+1
    val credentialCount=if(session.creatingAccount) 3 else 2
    val totalRequired=credentialCount+required.size+if(safeContact) 0 else 2
    val progressCompleted=credentialCount+completed
    val pageCompletion=listOf(true)+localCompletion
    fun pageValid()=when(pages[page]) {
        "Personal details" -> personalValid
        "Location" -> address.locationValid
        "Safe entry & cover" -> InputPolicy.validTrigger(trigger) && supportValid
        else -> true
    }
    fun collectedContacts()=(contacts.toList()+if(contactName.isNotBlank() && contactDetails.isNotBlank())
        listOf(SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())) else emptyList()).take(25)
    fun saveProfile()=session.saveSetupProfile(UserProfile(
        username=session.setupUsername,fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),
        dob=dob.trim(),language=language.trim(),situation=situation.trim(),region=address.generalLocation,
        triggerHash=if(safeContact) "" else InputPolicy.triggerHash(trigger),
        contacts=if(safeContact) emptyList() else collectedContacts(),
        address=address,coverType=coverType,role=session.accountRole))
    FormColumn {
        Heading("Your profile",pages[page])
        OnboardingProgress(progressCompleted,totalRequired,progressPage,stages,pageCompletion)
        AnimatedContent(targetState=pages[page],label="onboarding page") { current -> when(current) {
            "Personal details" -> Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Field("Full name (required)",fullName,"full_name",enabled=!session.busy,
                    error=if(showErrors && fullName.isBlank()) "Name required" else null) {
                    fullName=it.take(120);showErrors=false
                }
                BirthdayPicker(dob,"dob",!session.busy,error=if(showErrors && !InputPolicy.validDob(dob)) "Birthday required" else null) {
                    dob=it;showErrors=false
                }
                LanguagePicker(language,!session.busy) { language=it;showErrors=false }
                if(!safeContact) {
                    HorizontalDivider()
                    Field("Names of people experiencing abuse (optional)",affected,"affected_names",enabled=!session.busy) { affected=it.take(500) }
                    Field("Abuser's name (optional)",abuserName,"abuser_name",enabled=!session.busy) { abuserName=it.take(120) }
                    Field("Brief description (optional)",situation,"situation",multiline=true,enabled=!session.busy) { situation=it.take(2000) }
                }
            }
            "Location" -> Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                LocalizedText("Only your city is required.",color=Muted,style=MaterialTheme.typography.bodySmall)
                AddressFields(address,cityOnlyRequired=true,cityError=if(showErrors && !address.locationValid) "City required" else null) {
                    address=it;showErrors=false
                }
            }
            "Safe entry & cover" -> Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Field("Trigger word or phrase",trigger,"trigger",secret=true,showSecretToggle=true,enabled=!session.busy,
                    error=if(showErrors && !InputPolicy.validTrigger(trigger)) "Invalid trigger" else null) {
                    trigger=it.take(64);showErrors=false
                }
                RequirementBullet("3–64 characters",InputPolicy.validTrigger(trigger),trigger.isNotEmpty())
                HorizontalDivider()
                CoverPicker(coverType,!session.busy) { chosen ->
                    if(chosen==CoverType.GAME) session.error("In development") else coverType=chosen
                }
                LocalizedText("Name and icon update after leaving the app.",color=Muted,style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LocalizedText("Safe contacts · optional",style=MaterialTheme.typography.titleLarge)
                LocalizedText("Add one or more trusted people. Olive never contacts them automatically.",color=Muted,style=MaterialTheme.typography.bodySmall)
                contacts.forEach { contact ->
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(start=14.dp,top=8.dp,bottom=8.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LocalizedText(contact.name,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                                LocalizedText(contact.details,color=Muted,style=MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick={contacts.remove(contact)},enabled=!session.busy) {
                                Icon(Icons.Outlined.Close,"Remove safe contact")
                            }
                        }
                    }
                }
                Field("Contact name (optional)",contactName,"setup_contact_name",enabled=!session.busy && contacts.size<25,
                    error=if(showErrors && !supportValid) "Complete both fields" else null) {
                    contactName=it.take(120);showErrors=false
                }
                Field("Phone or email (optional)",contactDetails,"setup_contact_details",enabled=!session.busy && contacts.size<25,
                    error=if(showErrors && !supportValid) "Complete both fields" else null) {
                    contactDetails=it.take(200);showErrors=false
                }
                OutlinedButton(onClick={
                    contacts += SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())
                    contactName="";contactDetails="";showErrors=false
                },enabled=!session.busy && contactName.isNotBlank() && contactDetails.isNotBlank() && contacts.size<25,
                    modifier=Modifier.fillMaxWidth().testTag("add_onboarding_contact")) {
                    Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));LocalizedText("Add another contact")
                }
            }
        } }
        Status(session)
        val finalProfilePage=page==pages.lastIndex
        val last=safeContact && finalProfilePage
        OnboardingNavigation(back={showErrors=false;if(page>0) page-- else session.back()},next={
            showErrors=true
            if(pageValid()) {
                showErrors=false
                if(finalProfilePage) saveProfile() else page++
            }
        },last=last,nextTag=if(page==pages.lastIndex) "profile_submit" else "onboarding_next",enabled=!session.busy,
            nextLabel=if(!safeContact && page==pages.lastIndex) "Next" else null)
    }
}

// USER ONLY: an administrator cannot know this private trigger, so a provisioned user sets it personally on first login.
@Composable internal fun TriggerSetup(session:SessionViewModel) {
    var trigger by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val valid=InputPolicy.validTrigger(trigger)
    FormColumn {
        Heading("Set your private entry","Choose your trigger word.")
        OnboardingProgress(if(valid) 1 else 0,1,0,1,listOf(valid))
        Field("Trigger word or phrase",trigger,"trigger",secret=true,showSecretToggle=true,enabled=!session.busy,
            error=if(showErrors && !valid) "Invalid trigger" else null) { trigger=it.take(64);showErrors=false }
        RequirementBullet("3–64 characters",valid,trigger.isNotEmpty())
        Status(session)
        OnboardingNavigation(session::back,{showErrors=true;if(valid) session.saveProvisionedTrigger(trigger)},last=true,
            nextTag="trigger_setup_submit",enabled=!session.busy)
    }
}

// STAFF + ADMIN ONLY: temporary organization accounts replace their alias with a real email during first-login setup.
@Composable internal fun OrganizationProfileSetup(session:SessionViewModel) {
    val existing=session.setupProfile
    var fullName by remember(existing) { mutableStateOf(existing?.fullName.orEmpty()) }
    var dob by remember(existing) { mutableStateOf(existing?.dob.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var page by rememberSaveable(existing?.username) { mutableIntStateOf(0) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pages=listOf("Personal details","Contact details")
    val emailValid=android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val required=listOf(fullName.isNotBlank(),InputPolicy.validDob(dob),emailValid)
    fun pageValid()=if(page==0) fullName.isNotBlank() && InputPolicy.validDob(dob) else emailValid
    FormColumn {
        Heading("Complete your ${session.accountRole.title} profile",pages[page],"Olive Tree")
        OnboardingProgress(required.count { it },required.size,page,pages.size,
            listOf(fullName.isNotBlank() && InputPolicy.validDob(dob),emailValid))
        if(page==0) {
            Field("Full name (required)",fullName,"organization_full_name",enabled=!session.busy,
                error=if(showErrors && fullName.isBlank()) "Name required" else null) { fullName=it.take(120);showErrors=false }
            BirthdayPicker(dob,"organization_dob",!session.busy,
                error=if(showErrors && !InputPolicy.validDob(dob)) "Birthday required" else null) { dob=it;showErrors=false }
        } else {
            Field("Email (required)",email,"organization_email",enabled=!session.busy,
                error=if(showErrors && !emailValid) "Invalid email" else null) { email=it.take(254);showErrors=false }
            Field("Phone (optional)",phone,"organization_phone",enabled=!session.busy) { phone=it.take(40) }
        }
        Status(session)
        OnboardingNavigation(back={showErrors=false;if(page>0) page-- else session.back()},next={
            showErrors=true
            if(pageValid()) {
                showErrors=false
                if(page==pages.lastIndex) session.saveOrganizationProfile(fullName,dob,email,phone) else page++
            }
        },last=page==pages.lastIndex,nextTag=if(page==pages.lastIndex) "organization_profile_submit" else "onboarding_next",
            enabled=!session.busy)
    }
}
