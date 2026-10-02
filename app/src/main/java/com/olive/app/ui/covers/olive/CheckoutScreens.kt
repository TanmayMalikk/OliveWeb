package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.OrderLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatCardNumber(value: String) = value.filter(Char::isDigit).take(16).chunked(4).joinToString(" ")
private fun formatExpiry(value: String): String {
    val digits=value.filter(Char::isDigit).take(4)
    return if(digits.length<=2) digits else digits.take(2)+"/"+digits.drop(2)
}
private fun validExpiry(value: String): Boolean {
    val digits=value.filter(Char::isDigit)
    return digits.length==4 && digits.take(2).toIntOrNull() in 1..12
}
private fun DeliveryAddress.displayText() = listOf(line1,line2,city,province,postalCode,country)
    .filter(String::isNotBlank).joinToString(", ")

// UI sizing/text: checkout fields fill the dialog width and rely on Material field/error text sizes.
@Composable private fun CheckoutField(label: String, value: String, tag: String, numeric: Boolean=false, secret: Boolean=false,
    error: String?=null, onFocus: (() -> Unit)?=null, change: (String)->Unit) {
    OutlinedTextField(value=value,onValueChange=change,label={LocalizedText(label)},singleLine=true,
        modifier=Modifier.fillMaxWidth().testTag(tag).onFocusChanged { if(it.isFocused) onFocus?.invoke() },
        isError=error!=null,supportingText=error?.let { value -> { LocalizedText(value) } },
        visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=if(numeric) KeyboardType.NumberPassword else KeyboardType.Text))
}
// UI sizing: address fields stack at the shared full width so longer localized labels can wrap without narrower columns.
@Composable fun AddressFields(value: DeliveryAddress, cityOnlyRequired: Boolean=false,
    onFocus: ((String)->Unit)?=null, change: (DeliveryAddress)->Unit) {
    AddressAutocomplete(value,if(cityOnlyRequired) "Street address (optional)" else "Street address (required)",
        onFocus={onFocus?.invoke("Google address selection")},onChange=change)
    CheckoutField("Apartment / unit (optional)",value.line2,"address_line2",onFocus={onFocus?.invoke("Apartment / unit")}) { change(value.copy(line2=it.take(160))) }
    CheckoutField("City (required)",value.city,"address_city",onFocus={onFocus?.invoke("City")}) { change(value.copy(city=it.take(160))) }
    CheckoutField(if(cityOnlyRequired) "Province / state (optional)" else "Province / state (required)",value.province,"address_province",onFocus={onFocus?.invoke("Province / state")}) { change(value.copy(province=it.take(160))) }
    CheckoutField(if(cityOnlyRequired) "Postal / ZIP code (optional)" else "Postal / ZIP code (required)",value.postalCode,"address_postal",onFocus={onFocus?.invoke("Postal / ZIP code")}) { change(value.copy(postalCode=it.take(160))) }
    CheckoutField(if(cityOnlyRequired) "Country (optional)" else "Country (required)",value.country,"address_country",onFocus={onFocus?.invoke("Country")}) { change(value.copy(country=it.take(160))) }
}
// UI sizing/color: cover dialogs fill the screen with Cream, respect system/keyboard insets, and use 24dp padding with 14dp gaps.
@Composable private fun CoverDialog(onClose: ()->Unit, content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(),color=Cream) {
            val scrollState=rememberScrollState()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().oliveScrollbar(scrollState).verticalScroll(scrollState).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                TextButton(onClick=onClose) { LocalizedText("Back to menu") }
                content()
            }
        }
    }
}
// Add or change address fields here; SessionViewModel handles saving and auto-close.
// UI sizing/text: address headings use headlineMedium and controls fill the dialog width.
@Composable fun AddressDialog(address: DeliveryAddress, busy: Boolean, message: String?, onSave: (DeliveryAddress)->Unit, onClose: ()->Unit) {
    var draft by remember(address) { mutableStateOf(address) }
    CoverDialog(onClose) {
        LocalizedText("Delivery address",style=MaterialTheme.typography.headlineMedium)
        LocalizedText("Where should we deliver your food?")
        AddressFields(draft) { draft=it }
        message?.let { LocalizedText(it) }
        Button(onClick={onSave(draft.copy(line1=draft.line1.trim(),line2=draft.line2.trim(),city=draft.city.trim(),province=draft.province.trim(),postalCode=draft.postalCode.trim(),country=draft.country.trim()))},enabled=draft.valid && !busy,modifier=Modifier.fillMaxWidth().testTag("save_address")) { LocalizedText("Save delivery address") }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick=onClose) { LocalizedText("Done") }
    }
}

// Change checkout presentation here; keep payment fields temporary and the result declined.
// UI sizing/color: checkout sections use titleLarge, inline error colors, full-width buttons, and the shared dialog spacing.
@Composable fun CheckoutDialog(address: DeliveryAddress, totalCents: Int, foods: List<OrderLine>,
    onSaveAddress: (DeliveryAddress)->Unit, onAttempt: suspend (List<OrderLine>,Int,String)->Unit, onClose: ()->Unit) {
    val scope=rememberCoroutineScope()
    var shipping by remember { mutableStateOf(address) }
    var recipient by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var cardholder by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var securityCode by remember { mutableStateOf("") }
    var billing by remember { mutableStateOf("") }
    var useShippingForBilling by remember { mutableStateOf(true) }
    var declined by remember { mutableStateOf(false) }
    var placing by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    val numberDigits=number.count(Char::isDigit)
    val formValid=shipping.valid && recipient.isNotBlank() && cardholder.isNotBlank() && numberDigits in 12..16 &&
        validExpiry(expiry) && securityCode.length in 3..4 && (useShippingForBilling || billing.isNotBlank())
    CoverDialog(onClose) {
        if(declined) {
            LocalizedText("Payment declined",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("payment_declined"))
            LocalizedText("We couldn’t complete your payment. No charge was made and no order was placed.")
            if(historyError) LocalizedText("Order history could not be saved on this device.",color=MaterialTheme.colorScheme.error)
            Button(onClick=onClose,modifier=Modifier.fillMaxWidth()) { LocalizedText("Return to menu") }
        } else {
            LocalizedText("Checkout",style=MaterialTheme.typography.headlineMedium)
            LocalizedText("Delivery details",style=MaterialTheme.typography.titleLarge)
            AddressFields(shipping) { shipping=it }
            if(showErrors && !shipping.valid) LocalizedText("Enter a complete shipping address.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={onSaveAddress(shipping)},enabled=shipping.valid,modifier=Modifier.testTag("checkout_save_address")) { LocalizedText("Save delivery address") }
            CheckoutField("Recipient name",recipient,"checkout_recipient",error=if(showErrors && recipient.isBlank()) "Recipient name is required." else null) { recipient=it.take(120) }
            CheckoutField("Delivery instructions (optional)",instructions,"checkout_instructions") { instructions=it.take(500) }
            HorizontalDivider()
            LocalizedText("Payment details",style=MaterialTheme.typography.titleLarge)
            CheckoutField("Name on card",cardholder,"cardholder",error=if(showErrors && cardholder.isBlank()) "Name on card is required." else null) { cardholder=it.take(120) }
            // Keep card entry readable while the values remain temporary and never get saved.
            CheckoutField("Card number",number,"card_number",numeric=true,error=if(showErrors && numberDigits !in 12..16) "Enter a valid card number with no more than 16 digits." else null) { number=formatCardNumber(it) }
            CheckoutField("Expiry · MM/YY",expiry,"card_expiry",numeric=true,error=if(showErrors && !validExpiry(expiry)) "Enter a valid expiry as MM/YY." else null) { expiry=formatExpiry(it) }
            CheckoutField("Security code",securityCode,"card_cvc",numeric=true,secret=true,error=if(showErrors && securityCode.length !in 3..4) "Enter a 3 or 4 digit security code." else null) { securityCode=it.filter { c->c in '0'..'9' }.take(4) }
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked=useShippingForBilling,onCheckedChange={useShippingForBilling=it},modifier=Modifier.testTag("use_shipping_address"))
                LocalizedText("Use shipping address",modifier=Modifier.weight(1f))
            }
            if(useShippingForBilling) LocalizedText(shipping.displayText(),style=MaterialTheme.typography.bodySmall)
            else CheckoutField("Billing address",billing,"billing_address",error=if(showErrors && billing.isBlank()) "Billing address is required." else null) { billing=it.take(500) }
            LocalizedText("Subtotal: " + java.text.NumberFormat.getCurrencyInstance(if(LocalOliveLanguage.current=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA).format(totalCents/100.0))
            Button(onClick={
                showErrors=true
                if(formValid) {
                    placing=true
                    scope.launch {
                        // Give the payment screen a believable processing beat, then always decline locally.
                        delay(1_200)
                        historyError=runCatching { onAttempt(foods,totalCents,"We couldn’t complete your payment. No charge was made and no order was placed.") }.isFailure
                        recipient="";instructions="";cardholder="";number="";expiry="";securityCode="";billing="";shipping=DeliveryAddress()
                        placing=false;declined=true
                    }
                }
            },modifier=Modifier.fillMaxWidth().testTag("place_order"),enabled=!placing) { LocalizedText(if(placing) "Processing payment…" else "Place order") }
            if(placing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("payment_processing"))
        }
    }
}

