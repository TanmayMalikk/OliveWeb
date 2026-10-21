package com.olive.app.ui.location

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.*
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal data class LocationMapConfiguration(
    val identity:AccountIdentity,
    val profile:UserProfile,
    val friends:List<Friend>,
    val localMode:Boolean,
    val youLabel:String,
    val savedLocationLabel:String,
)

internal data class LocationMapState(
    val ownerUid:String?=null,
    val ownPoint:MapLocationPoint?=null,
    val livePoint:MapLocationPoint?=null,
    val contacts:List<MapLocationPoint> = emptyList(),
    val selected:Set<String> = emptySet(),
    val liveActive:Boolean=false,
    val expiresAt:Long=0L,
    val staticSharing:Boolean=false,
    val working:Boolean=false,
    val error:String?=null,
) {
    fun displayedOwn(role:AccountRole)=if(role==AccountRole.USER) livePoint ?: ownPoint else ownPoint
}

/** Coordinates map data, subscriptions and sharing while keeping Android/Firebase work out of Compose. */
internal class LocationMapController(
    context:Context,
    private val scope:CoroutineScope,
    private val repository:LocationSharingDataSource?,
) {
    private val applicationContext=context.applicationContext
    private var locationJob:Job?=null
    private var expiryJob:Job?=null
    private var subscription:ChatSubscription?=null
    private var generation=0
    var state by mutableStateOf(LocationMapState())
        private set

    init {
        scope.launch { LiveLocationState.current.collectLatest { state=state.copy(livePoint=it) } }
    }

    fun open(configuration:LocationMapConfiguration,requestPermission:suspend ()->Boolean) {
        closeMap()
        val token=++generation
        val uid=configuration.identity.uid
        val friendIds=configuration.friends.mapTo(mutableSetOf()) { it.uid }
        state=LocationMapState(
            ownerUid=uid,
            livePoint=LiveLocationState.current.value,
            selected=LiveLocationController.selected(applicationContext).intersect(friendIds),
            liveActive=LiveLocationController.active(applicationContext),
            expiresAt=LiveLocationController.expiresAt(applicationContext),
            staticSharing=repository?.staticSharingEnabled(uid)==true,
        )
        if(!configuration.localMode) {
            subscription=if(configuration.profile.role==AccountRole.USER)
                repository?.observeStaticContacts(uid,configuration.friends) { if(token==generation) state=state.copy(contacts=it) }
            else repository?.observeLiveFriends(uid,configuration.friends) { if(token==generation) state=state.copy(contacts=it) }
        }
        locationJob=scope.launch {
            if(configuration.profile.role==AccountRole.USER && requestPermission()) {
                while(isActive) {
                    DeviceLocation.currentCoordinates(applicationContext)?.let { fix -> if(token==generation) {
                        val previous=state.ownPoint
                        val trail=previous?.trail.orEmpty().filter { it.updatedAt>=System.currentTimeMillis()-TRAIL_WINDOW_MILLIS }.toMutableList()
                        val moved=previous==null || FloatArray(1).also { result -> Location.distanceBetween(
                            previous.latitude,previous.longitude,fix.latitude,fix.longitude,result) }[0]>=MIN_TRAIL_DISTANCE_METERS
                        if(moved && trail.lastOrNull()?.updatedAt!=fix.timestamp)
                            trail += LocationTrailPoint(fix.latitude,fix.longitude,fix.timestamp)
                        state=state.copy(ownPoint=MapLocationPoint(uid,configuration.youLabel,fix.latitude,fix.longitude,
                            fix.timestamp,true,fix.accuracy,trail=trail.takeLast(MAX_TRAIL_POINTS)))
                    } }
                    delay(MAP_LOCATION_REFRESH_MILLIS)
                }
            } else if(configuration.profile.role==AccountRole.SAFE_CONTACT) {
                val coordinates=GeoapifyClient.coordinates(configuration.profile.address)
                    ?: DeviceLocation.coordinatesForAddress(applicationContext,configuration.profile.address)
                coordinates?.let { (latitude,longitude) -> if(token==generation) {
                    state=state.copy(ownPoint=MapLocationPoint(uid,configuration.savedLocationLabel,latitude,longitude,System.currentTimeMillis(),false))
                } }
                if(state.staticSharing && !configuration.localMode) updateStaticSharing(configuration,true)
            }
        }
        watchExpiry()
    }

    fun closeMap() {
        generation++
        locationJob?.cancel();locationJob=null
        expiryJob?.cancel();expiryJob=null
        subscription?.close();subscription=null
    }

    fun clear() { closeMap();state=LocationMapState() }

    fun selectContact(uid:String,enabled:Boolean) {
        if(state.liveActive) return
        val updated=if(enabled) state.selected+uid else state.selected-uid
        if(updated.size<=MAX_SHARED_CONTACTS) state=state.copy(selected=updated)
    }

    fun startSharing(configuration:LocationMapConfiguration,durationMillis:Long,requestPermission:suspend ()->Boolean,
        permissionError:String,startError:String) {
        val recipients=configuration.friends.filter { it.uid in state.selected }
        if(configuration.localMode || recipients.isEmpty() || state.working) return
        scope.launch {
            state=state.copy(working=true,error=null)
            if(!requestPermission()) state=state.copy(working=false,error=permissionError)
            else try {
                val end=System.currentTimeMillis()+durationMillis
                val startedAt=System.currentTimeMillis()
                val key=checkNotNull(checkNotNull(repository).setLiveRecipients(configuration.identity.uid,recipients))
                LiveLocationController.start(applicationContext,configuration.identity.uid,configuration.profile.fullName,state.selected,end,key)
                withTimeout(20_000L) { LiveLocationState.current.filterNotNull()
                    .first { it.uid==configuration.identity.uid && it.updatedAt>=startedAt } }
                state=state.copy(working=false,liveActive=true,expiresAt=end)
                watchExpiry()
            } catch(failure:Exception) {
                LiveLocationController.stop(applicationContext)
                runCatching { repository?.setLiveRecipients(configuration.identity.uid,emptyList()) }
                state=state.copy(working=false,error=failure.localizedMessage ?: startError)
            }
        }
    }

    fun stopSharing() {
        LiveLocationController.stop(applicationContext)
        state=state.copy(liveActive=false,expiresAt=0L)
        expiryJob?.cancel();expiryJob=null
    }

    fun updateStaticSharing(configuration:LocationMapConfiguration,enabled:Boolean) {
        if(configuration.localMode || state.working) return
        scope.launch {
            state=state.copy(working=true,error=null)
            runCatching { checkNotNull(repository).setStaticSharing(configuration.identity,configuration.profile,configuration.friends,enabled) }
                .onSuccess { state=state.copy(staticSharing=enabled) }
                .onFailure { failure -> state=state.copy(error=if(failure is SavedLocationUnavailableException)
                    applicationContext.getString(com.olive.app.R.string.location_saved_address_unavailable) else failure.localizedMessage) }
            state=state.copy(working=false)
        }
    }

    private fun watchExpiry() {
        expiryJob?.cancel()
        expiryJob=scope.launch {
            while(state.liveActive && isActive) {
                if(state.expiresAt<=System.currentTimeMillis()) state=state.copy(liveActive=false)
                delay(1_000L)
            }
        }
    }

    private companion object {
        const val MAX_SHARED_CONTACTS=10
        const val MAP_LOCATION_REFRESH_MILLIS=2_000L
        const val TRAIL_WINDOW_MILLIS=30*60_000L
        const val MIN_TRAIL_DISTANCE_METERS=2f
        const val MAX_TRAIL_POINTS=1_000
    }
}
