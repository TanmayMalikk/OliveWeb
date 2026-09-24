package com.olive.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.olive.app.data.Dish
import com.olive.app.data.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

// Change remote menu photos here and edit the offline fallback drawings in FoodArt.
@Composable fun DishImage(dish: Dish, modifier: Modifier=Modifier) {
    if(dish.imageUrl.isBlank()) { FoodArt(dish.art,modifier); return }
    val bitmap by produceState<ImageBitmap?>(null,dish.imageUrl) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                val uri=URI(dish.imageUrl); require(uri.scheme=="https")
                val connection=uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout=8000; connection.readTimeout=8000;connection.instanceFollowRedirects=false
                    check(connection.responseCode==200)
                    val bytes=connection.inputStream.use { it.readLimited(5_000_000) };check(bytes.size<=5_000_000)
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    check(bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000)
                    val options=BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/1024).coerceAtLeast(1) }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.asImageBitmap()
                } finally { connection.disconnect() }
            }.getOrNull()
        }
    }
    if(bitmap!=null) Image(bitmap!!,dish.name,modifier,contentScale=ContentScale.Crop)
    else Box(modifier) { LocalizedText("Food photo unavailable") }
}
