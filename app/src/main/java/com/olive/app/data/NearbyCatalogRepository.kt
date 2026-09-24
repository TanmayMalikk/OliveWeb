package com.olive.app.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

// Connect the real menu JSON here; keep provider keys on the HTTPS backend, never in the APK.
class NearbyCatalogRepository(private val endpoint: String) {
    suspend fun dishes(address: DeliveryAddress): List<Dish> {
        require(URI(endpoint).scheme=="https")
        val token=FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: error("Sign in required")
        return withContext(Dispatchers.IO) {
            val query=listOf("city" to address.city,"region" to address.province,"country" to address.country)
                .joinToString("&") { (k,v)-> "$k=${URLEncoder.encode(v,"UTF-8")}" }
            val connection=URI("$endpoint?$query").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=10_000;connection.readTimeout=10_000
                connection.instanceFollowRedirects=false
                connection.setRequestProperty("Authorization","Bearer $token")
                check(connection.responseCode==200)
                val bytes=connection.inputStream.use { it.readLimited(1_000_000) }
                check(bytes.size<=1_000_000)
                val items=JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("items")
                (0 until minOf(items.length(),100)).map { index ->
                    val item=items.getJSONObject(index)
                    val image=item.getString("imageUrl")
                    val source=item.getString("sourceUrl")
                    require(URI(image).scheme=="https" && URI(source).scheme=="https")
                    require(item.getBoolean("itemImageVerified"))
                    require(item.getString("currency")=="CAD")
                    require(item.getInt("priceCents") in 0..100_000)
                    Dish(item.getString("id"),item.getString("name"),item.getString("restaurant"),item.getString("category"),
                        item.getString("description"),item.getInt("priceCents"),item.optString("minutes","—"),
                        item.optString("rating","—"),"",image,item.getString("imageCredit"),source)
                }.distinctBy { it.id }
            } finally { connection.disconnect() }
        }
    }
}

internal fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        val count=read(buffer)
        if(count<0) break
        check(output.size()+count<=limit) { "Response exceeds limit" }
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
