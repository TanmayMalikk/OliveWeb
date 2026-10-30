// Food cover users: shows cart and checkout steps.
package com.olive.app.ui.covers.food

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.rememberResourceText
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.OrderLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.olive.app.ui.shared.LocalizedIcon as Icon
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight

// Formats card number.
private fun formatCardNumber(value: String) =
    value.filter(Char::isDigit).take(16).chunked(4).joinToString(" ")

// Formats expiry.
private fun formatExpiry(value: String): String {
    val digits = value.filter(Char::isDigit).take(4)
    return if (digits.length <= 2) digits else digits.take(2) + "/" + digits.drop(2)
}

private fun validExpiry(value: String): Boolean {
    val digits = value.filter(Char::isDigit)
    return digits.length == 4 && digits.take(2).toIntOrNull() in 1..12
}

@Composable
internal fun CheckoutField(
    label: String, value: String, tag: String, numeric: Boolean = false, secret: Boolean = false,
    error: String? = null, onFocus: (() -> Unit)? = null, change: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        isError = error != null,
        supportingText = error?.let { value -> { AccessibleMessage(value) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Text
        )
    )
}

// UI sizing: address fields stack at the shared full width so longer localized labels can wrap without narrower columns.
@Composable
internal fun CheckoutDialog(
    address: DeliveryAddress,
    totalCents: Int,
    foods: List<OrderLine>,
    onSaveAddress: (DeliveryAddress) -> Unit,
    onAttempt: suspend (List<OrderLine>, Int, String) -> Unit,
    onClose: () -> Unit
) {
    val uiText = rememberResourceText()
    val scope = rememberCoroutineScope()
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
    val numberDigits = number.count(Char::isDigit)
    val formValid =
        shipping.valid && recipient.isNotBlank() && cardholder.isNotBlank() && numberDigits in 12..16 &&
                validExpiry(expiry) && securityCode.length in 3..4 && (useShippingForBilling || billing.isNotBlank())
    CoverDialog(onClose) {
        if (declined) {
            Text(
                stringResource(R.string.payment_declined),
                style = MaterialTheme.typography.headlineMedium,
                modifier = (Modifier.testTag("payment_declined")).semantics { heading() })
            Text(stringResource(R.string.we_couldn_t_complete_your_payment_no_charge_was_made_and_no_order_was_placed))
            if (historyError) Text(
                stringResource(R.string.order_history_could_not_be_saved_on_this_device),
                color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.return_to_menu)) }
        } else {
            Text(
                stringResource(R.string.checkout),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.delivery_details),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            AddressFields(shipping) { shipping = it }
            if (showErrors && !shipping.valid) Text(
                stringResource(R.string.enter_a_complete_shipping_address),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { onSaveAddress(shipping) },
                enabled = shipping.valid,
                modifier = Modifier.testTag("checkout_save_address")
            ) { Text(stringResource(R.string.save_delivery_address)) }
            CheckoutField(
                uiText(R.string.recipient_name),
                recipient,
                "checkout_recipient",
                error = if (showErrors && recipient.isBlank()) uiText(R.string.recipient_name_is_required) else null
            ) { recipient = it.take(120) }
            CheckoutField(
                uiText(R.string.delivery_instructions_optional),
                instructions,
                "checkout_instructions"
            ) { instructions = it.take(500) }
            HorizontalDivider()
            Text(
                stringResource(R.string.payment_details),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            CheckoutField(
                uiText(R.string.name_on_card),
                cardholder,
                "cardholder",
                error = if (showErrors && cardholder.isBlank()) uiText(R.string.name_on_card_is_required) else null
            ) { cardholder = it.take(120) }
            // Keep card entry readable while the values remain temporary and never get saved.
            CheckoutField(
                uiText(R.string.card_number),
                number,
                "card_number",
                numeric = true,
                error = if (showErrors && numberDigits !in 12..16) uiText(R.string.enter_a_valid_card_number_with_no_more_than_16_digits) else null
            ) { number = formatCardNumber(it) }
            CheckoutField(
                uiText(R.string.expiry_mm_yy),
                expiry,
                "card_expiry",
                numeric = true,
                error = if (showErrors && !validExpiry(expiry)) uiText(R.string.enter_a_valid_expiry_as_mm_yy) else null
            ) { expiry = formatExpiry(it) }
            CheckoutField(
                uiText(R.string.security_code),
                securityCode,
                "card_cvc",
                numeric = true,
                secret = true,
                error = if (showErrors && securityCode.length !in 3..4) uiText(R.string.enter_a_3_or_4_digit_security_code) else null
            ) { securityCode = it.filter { c -> c in '0'..'9' }.take(4) }
            Row(
                Modifier.fillMaxWidth().testTag("use_shipping_address").toggleable(
                    value = useShippingForBilling, role = Role.Checkbox,
                    onValueChange = { useShippingForBilling = it }),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Checkbox(checked = useShippingForBilling, onCheckedChange = null)
                Text(stringResource(R.string.use_shipping_address), modifier = Modifier.weight(1f))
            }
            if (useShippingForBilling) Text(
                shipping.displayText(),
                style = MaterialTheme.typography.bodySmall
            )
            else CheckoutField(
                uiText(R.string.billing_address),
                billing,
                "billing_address",
                error = if (showErrors && billing.isBlank()) uiText(R.string.billing_address_is_required) else null
            ) { billing = it.take(500) }
            Text(stringResource(R.string.subtotal) + "$%.2f".format(Locale.US, totalCents / 100.0))
            Button(
                onClick = {
                    showErrors = true
                    if (formValid) {
                        placing = true
                        scope.launch {
                            // Give the payment screen a believable processing beat, then always decline locally.
                            delay(1_200.milliseconds)
                            historyError = runCatching {
                                onAttempt(
                                    foods,
                                    totalCents,
                                    uiText(R.string.we_couldn_t_complete_your_payment_no_charge_was_made_and_no_order_was_placed)
                                )
                            }.isFailure
                            recipient = ""
                            instructions = ""
                            cardholder = ""
                            number =
                                ""
                            expiry = ""
                            securityCode = ""
                            billing = ""
                            shipping =
                                DeliveryAddress()
                            placing = false
                            declined = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("place_order"),
                enabled = !placing
            ) { Text(if (placing) uiText(R.string.processing_payment) else uiText(R.string.place_order)) }
            if (placing) LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag("payment_processing")
            )
        }
    }
}

// Hearts land here so users can find their saved food again.
// UI sizing: saved food uses 24dp vertical padding and 20dp gaps while reusing the shared dish-card sizes.
@Composable
internal fun SavedDishesScreen(vm: OliveViewModel) {
    val uiText = rememberResourceText()
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            FoodPageTitle(
                uiText(R.string.your_favourites),
                uiText(R.string.good_finds_kept_close)
            )
        }
        val saved = vm.dishes.filter { it.id in vm.favorites }
        if (saved.isEmpty()) item {
            FoodEmptyState(
                Icons.Outlined.FavoriteBorder,
                uiText(R.string.a_place_for_your_favourites),
                uiText(R.string.tap_the_heart_on_any_dish_to_save_it_here)
            )
        }
        items(saved, key = { it.id }) { DishCard(it, vm, Modifier.animateItem()) }
    }
}

// Cart details are grouped for TalkBack with separate quantity actions; checkout always declines locally.
// UI sizing: cart rows use 24dp sides, 72dp thumbnails, 12dp padding, and full-width checkout controls.
@Composable
internal fun FoodCartScreen(vm: OliveViewModel, checkout: () -> Unit) {
    val uiText = rememberResourceText()
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            FoodPageTitle(
                uiText(R.string.your_cart),
                uiText(R.string.a_few_good_things_all_together)
            )
        }
        if (vm.itemCount == 0) item {
            FoodEmptyState(
                Icons.Outlined.ShoppingBag,
                uiText(R.string.something_delicious_belongs_here),
                uiText(R.string.explore_the_menu_and_add_a_dish_to_get_started)
            )
            Button(
                onClick = { vm.navigate(CoverTab.Discover) },
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()
            ) { Text(stringResource(R.string.explore_the_menu)) }
        }
        items(vm.dishes.filter { it.id in vm.cart }, key = { it.id }) { dish ->
            Card(
                modifier = Modifier.animateItem().padding(horizontal = 24.dp).animateContentSize()
                    .testTag("cart_row_${dish.id}").semantics(mergeDescendants = true) {},
                colors = CardDefaults.cardColors(containerColor = Paper)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DishImage(
                        dish,
                        Modifier.size(72.dp).clip(RoundedCornerShape(14.dp))
                            .clearAndSetSemantics {})
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(uiText.dishName(dish), fontWeight = FontWeight.SemiBold)
                        Text(formatDishPrice(dish.priceCents), color = Muted)
                        val choices = vm.selectedIngredients(dish)
                        if (choices.isNotEmpty()) Text(choices.joinToString(", ") {
                            uiText.ingredient(
                                it
                            )
                        }, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                vm.changeQuantity(
                                    dish.id,
                                    -1
                                )
                            }) {
                                Icon(
                                    Icons.Outlined.Remove,
                                    uiText(R.string.ui_remove_one_value, uiText.dishName(dish))
                                )
                            }
                            Text("${vm.cart[dish.id]}")
                            IconButton(
                                onClick = { vm.changeQuantity(dish.id, 1) },
                                enabled = (vm.cart[dish.id] ?: 0) < 99
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    uiText(R.string.ui_add_one_value, uiText.dishName(dish))
                                )
                            }
                        }
                    }
                }
            }
        }
        if (vm.itemCount > 0) item {
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.subtotal),
                        modifier = Modifier.weight(1f)
                    )
                    Text(formatDishPrice(vm.total), fontWeight = FontWeight.Bold)
                }
                Text(
                    stringResource(R.string.review_your_delivery_and_payment_details_at_checkout),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = checkout,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                ) { Text(stringResource(R.string.continue_to_checkout)) }
            }
        }
    }
}

// The provider stays behind this field, so onboarding and cover forms share one accessible address workflow.
