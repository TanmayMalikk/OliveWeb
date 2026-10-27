// Contains the dishes code for this part of Olive.
package com.olive.app.ui.covers.olive

import com.olive.app.ui.shared.*

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.Dish
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.rememberResourceText
import kotlin.math.cos
import kotlin.math.sin

// Dish details form one TalkBack target; the favourite button keeps its own action and the photo is decorative.
// UI sizing/color: dish cards use 24dp sides, 22dp corners, a 16:9 image, 16dp padding, and 18sp item names.
@Composable
internal fun DishCard(dish: Dish, vm: OliveViewModel, modifier: Modifier = Modifier) {
    val uiText = rememberResourceText()
    Card(
        onClick = { vm.select(dish) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).animateContentSize()
            .testTag("dish_card_${dish.id}").semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Paper)
    ) {
        Box {
            DishImage(dish, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clearAndSetSemantics {})
            FilledIconButton(
                onClick = { vm.favorite(dish.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Cream)
            ) {
                Icon(
                    if (dish.id in vm.favorites) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    if (dish.id in vm.favorites) uiText(
                        R.string.ui_unsave_value,
                        uiText.dishName(dish)
                    ) else uiText(R.string.ui_save_value, uiText.dishName(dish)),
                    tint = OliveGreen
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    uiText.dishName(dish),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatDishPrice(dish.priceCents),
                    fontWeight = FontWeight.SemiBold,
                    color = OliveGreen
                )
            }
            Text(
                uiText(
                    R.string.ui_value_value,
                    uiText.dishRestaurant(dish),
                    uiText.foodFilter(dish.diet)
                ), color = Muted, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                uiText(R.string.ui_value_value_min, dish.rating, dish.minutes),
                style = MaterialTheme.typography.labelMedium,
                color = OliveGreen
            )
        }
    }
}

// Tap a dish and this gives it the full spotlight before adding it to the cart.
// UI sizing/text: details use 20dp gaps, a 16:9 image, 24dp sides, and a 34/38sp food title.
@Composable
internal fun DishDetailsScreen(dish: Dish, vm: OliveViewModel) {
    val uiText = rememberResourceText()
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.select(null) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        uiText(R.string.back_to_menu)
                    )
                }
                Text(uiText.dishRestaurant(dish), style = MaterialTheme.typography.titleMedium)
            }
        }
        item { DishImage(dish, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
        item {
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    uiText.dishCategory(dish).uppercase(),
                    color = OliveGreen,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.sp
                )
                Text(
                    uiText.dishName(dish),
                    fontFamily = FontFamily.Serif,
                    fontSize = 34.sp,
                    lineHeight = 38.sp
                )
                Text(
                    uiText(R.string.ui_value_value_min_fe5b84, dish.rating, dish.minutes),
                    color = Muted
                )
                Text(uiText.dishDescription(dish), style = MaterialTheme.typography.bodyLarge)
                if (dish.ingredients.isNotEmpty()) {
                    Text(
                        stringResource(R.string.customize_ingredients),
                        style = MaterialTheme.typography.titleMedium
                    )
                    dish.ingredients.forEach { ingredient ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = ingredient in vm.selectedIngredients(dish),
                                role = Role.Checkbox,
                                onValueChange = { vm.toggleIngredient(dish, ingredient) }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = ingredient in vm.selectedIngredients(dish),
                                onCheckedChange = null
                            )
                            Text(uiText.ingredient(ingredient))
                        }
                    }
                }
                Text(
                    formatDishPrice(dish.priceCents),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OliveGreen,
                    modifier = Modifier.semantics { heading() })
                Button(
                    onClick = { vm.changeQuantity(dish.id, 1) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    enabled = (vm.cart[dish.id] ?: 0) < 99
                ) {
                    Text(
                        if ((vm.cart[dish.id] ?: 0) == 0) uiText(R.string.add_to_cart) else uiText(
                            R.string.ui_add_another_value_in_cart,
                            vm.cart[dish.id]
                        )
                    )
                }
                OutlinedButton(
                    onClick = { vm.favorite(dish.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (dish.id in vm.favorites) uiText(R.string.saved_to_favourites) else uiText(
                            R.string.save_for_later
                        )
                    )
                }
            }
        }
    }
}

private fun localDishPhoto(id: String) = when (id) {
    "bowl" -> R.drawable.menu_bowl
    "bowl2" -> R.drawable.menu_bowl2
    "bowl3" -> R.drawable.menu_bowl3
    "bowl4" -> R.drawable.menu_bowl4
    "pizza" -> R.drawable.menu_pizza
    "pizza2" -> R.drawable.menu_pizza2
    "pizza3" -> R.drawable.menu_pizza3
    "pizza4" -> R.drawable.menu_pizza4
    "burger" -> R.drawable.menu_burger
    "burger2" -> R.drawable.menu_burger2
    "burger3" -> R.drawable.menu_burger3
    "wrap1" -> R.drawable.menu_wrap1
    "wrap2" -> R.drawable.menu_wrap2
    "pasta1" -> R.drawable.menu_pasta1
    "pasta2" -> R.drawable.menu_pasta2
    "pasta3" -> R.drawable.menu_pasta3
    "salad1" -> R.drawable.menu_salad1
    "salad2" -> R.drawable.menu_salad2
    "soup1" -> R.drawable.menu_soup1
    "plate1" -> R.drawable.menu_plate1
    "plate2" -> R.drawable.menu_plate2
    else -> 0
}

// Olive's homemade menu uses bundled photos so every dish still works without a network connection.
// UI sizing/color: callers set the image aspect ratio/size; this layer crops photos to the supplied bounds and uses a themed fallback.
@Composable
internal fun DishImage(dish: Dish, modifier: Modifier = Modifier) {
    val uiText = com.olive.app.ui.shared.rememberResourceText()
    val localPhoto = localDishPhoto(dish.id)
    if (localPhoto != 0) {
        Image(
            painterResource(localPhoto),
            uiText.dishName(dish),
            modifier,
            contentScale = ContentScale.Crop
        )
        return
    }
    FoodArt(dish.art, modifier)
}

// Lightweight food illustrations keep the sample menu looking polished without random web photos.
// UI sizing/color: this fallback art scales from the supplied canvas size; edit the palette below to change food illustration colors.
@Composable
private fun FoodArt(kind: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        val bg = when (kind) {
            "pizza" -> Color(0xFFEAD9C4)
            "burger" -> Color(0xFFF0DFC4)
            "sushi" -> Color(0xFFD9E4DF)
            else -> Color(
                0xFFDEE5D0
            )
        }
        drawRect(bg)
        drawCircle(Color.White.copy(alpha = .25f), s * .65f, Offset(size.width * .1f, 0f))
        drawCircle(Color(0xFF253322).copy(alpha = .10f), s * .42f, c + Offset(4f, 9f))
        drawCircle(Color(0xFFFFFDF4), s * .42f, c)
        drawCircle(Color(0xFFE6E3D6), s * .36f, c, style = Stroke(s * .007f))
        when (kind) {
            "pizza" -> {
                drawCircle(Color(0xFFDCA252), s * .33f, c)
                drawCircle(Color(0xFFB95035), s * .285f, c)
                for (i in 0..7) {
                    val a = i * .91f
                    val p = c + Offset(cos(a) * s * .18f, sin(a) * s * .18f)
                    drawCircle(Color(0xFFFFE7AA), s * .07f, p)
                    drawOval(Color(0xFF547640), p + Offset(s * .02f, 0f), Size(s * .09f, s * .035f))
                }
            }

            "burger" -> {
                val left = c.x - s * .28f
                fun layer(y: Float, h: Float, color: Color) = drawRoundRect(
                    color,
                    Offset(left, c.y + s * y),
                    Size(s * .56f, s * h),
                    androidx.compose.ui.geometry.CornerRadius(s * .06f)
                )
                layer(.13f, .12f, Color(0xFFDFA555))
                layer(.06f, .10f, Color(0xFF6D4029))
                layer(.01f, .065f, Color(0xFFF3C653))
                layer(-.055f, .075f, Color(0xFFB95D40))
                layer(-.105f, .065f, Color(0xFF6D9045))
                drawArc(
                    Color(0xFFE7B363),
                    180f,
                    180f,
                    true,
                    Offset(left, c.y - s * .34f),
                    Size(s * .56f, s * .48f)
                )
                for (i in 0..7) drawOval(
                    Color(0xFFFFE8BD),
                    Offset(left + s * (.09f + i * .05f), c.y - s * (.16f + (i % 3) * .035f)),
                    Size(s * .025f, s * .012f)
                )
            }

            "sushi" -> {
                for (row in 0..1) for (col in 0..2) {
                    val p = c + Offset((col - 1) * s * .20f, (row - .5f) * s * .23f)
                    drawCircle(Color(0xFF344D3D), s * .093f, p)
                    drawCircle(Color(0xFFF3F0DE), s * .077f, p)
                    drawCircle(Color(0xFFDD9474), s * .045f, p)
                    drawCircle(Color(0xFF86A65F), s * .025f, p + Offset(s * .025f, 0f))
                }
            }

            else -> {
                drawCircle(Color(0xFF8EA664), s * .32f, c)
                for (i in 0..29) {
                    val a = i * 2.399f
                    val r = s * (.04f + (i % 6) * .045f)
                    val p = c + Offset(cos(a) * r, sin(a) * r)
                    drawCircle(
                        listOf(
                            Color(0xFF527547),
                            Color(0xFFC9D88C),
                            Color(0xFFE4C893),
                            Color(0xFFB96546)
                        )[i % 4], s * .043f, p
                    )
                }
                for (i in 0..4) drawArc(
                    Color(0xFFD6DFA0),
                    110f,
                    140f,
                    false,
                    c - Offset(s * .20f - i * s * .023f, s * .19f),
                    Size(s * .27f, s * .38f),
                    style = Stroke(s * .026f)
                )
            }
        }
    }
}
