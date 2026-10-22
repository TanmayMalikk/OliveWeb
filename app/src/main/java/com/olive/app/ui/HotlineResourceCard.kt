package com.olive.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.HotlineResource
import com.olive.app.data.HotlineResourceKind
import com.olive.app.data.hotlineDialNumber

@Composable internal fun HotlineResourceCard(resource:HotlineResource,onOpen:(String,Boolean)->Unit) {
    Card(Modifier.fillMaxWidth().testTag("hotline_${resource.id}"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant,contentColor=Ink)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(resource.name,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                Text(stringResource(if(resource.kind==HotlineResourceKind.Hotline) R.string.support_resource_hotline else R.string.support_resource_shelter),
                    color=OliveGreen,style=MaterialTheme.typography.labelLarge)
                Text("${resource.city}, ${hotlineRegionName(resource.region)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                Text(resource.description,style=MaterialTheme.typography.bodyMedium)
                Text(resource.phone,style=MaterialTheme.typography.headlineSmall)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                val dialLabel=stringResource(R.string.support_dial_label,resource.name,resource.phone)
                OutlinedButton(onClick={onOpen(resource.phoneUri,true)},modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=dialLabel }) {
                    Icon(Icons.Outlined.Phone,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.support_open_dialer))
                }
                val detailsLabel=stringResource(R.string.support_details_label,resource.name)
                TextButton(onClick={onOpen(resource.website,false)},modifier=Modifier.heightIn(min=48.dp).semantics { contentDescription=detailsLabel }) {
                    Text(stringResource(R.string.support_service_details));Spacer(Modifier.width(8.dp));Icon(Icons.AutoMirrored.Outlined.OpenInNew,null)
                }
            }
        }
    }
}

// Opening a dialer never calls automatically and needs no phone permission. Android leaving the app locks the safe area.
internal fun openHotlineDestination(context:Context,value:String,dial:Boolean):Boolean {
    val uri=value.toUri()
    if(if(dial) uri.scheme!="tel" else uri.scheme!="https" || uri.host.isNullOrBlank()) return false
    val destination=if(dial) Uri.fromParts("tel",hotlineDialNumber(value) ?: return false,null) else uri
    return try {
        context.startActivity(Intent(if(dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW,destination))
        true
    } catch(_:ActivityNotFoundException) { false } catch(_:SecurityException) { false }
}

@Composable internal fun hotlineRegionName(code:String)=stringResource(when(code) {
    "AB" -> R.string.support_region_ab;"BC" -> R.string.support_region_bc;"MB" -> R.string.support_region_mb
    "NB" -> R.string.support_region_nb;"NL" -> R.string.support_region_nl;"NS" -> R.string.support_region_ns
    "NT" -> R.string.support_region_nt;"NU" -> R.string.support_region_nu;"ON" -> R.string.support_region_on
    "PE" -> R.string.support_region_pe;"QC" -> R.string.support_region_qc;"SK" -> R.string.support_region_sk
    "YT" -> R.string.support_region_yt;else -> R.string.support_canada
})
