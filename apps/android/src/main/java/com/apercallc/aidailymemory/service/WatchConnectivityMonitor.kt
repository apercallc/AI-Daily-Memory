package com.apercallc.aidailymemory.service

import android.content.Context
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.health.WatchLink
import com.apercallc.aidailymemory.recording.health.watchLinkFromCapabilityState
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Observes the companion watch only while capture health is being displayed. */
object WatchConnectivityMonitor {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(context: Context): Flow<WatchLink> = callbackFlow {
        // Capture health must remain available even when Play Services is absent or slow.
        trySend(WatchLink.UNKNOWN)
        val client = Wearable.getCapabilityClient(context.applicationContext)
        val generation = AtomicLong()

        fun refresh(reachableNow: Boolean? = null) {
            val request = generation.incrementAndGet()
            client.getCapability(WATCH_CONTROL_CAPABILITY, CapabilityClient.FILTER_ALL)
                .addOnSuccessListener { all ->
                    if (generation.get() != request) return@addOnSuccessListener
                    val installed = all.nodes.isNotEmpty()
                    if (!installed || reachableNow != null) {
                        trySend(watchLinkFromCapabilityState(installed, reachableNow))
                    } else {
                        client.getCapability(
                            WATCH_CONTROL_CAPABILITY,
                            CapabilityClient.FILTER_REACHABLE,
                        ).addOnSuccessListener { reachable ->
                            if (generation.get() == request) {
                                trySend(
                                    watchLinkFromCapabilityState(
                                        installed = true,
                                        reachable = reachable.nodes.isNotEmpty(),
                                    ),
                                )
                            }
                        }.addOnFailureListener { error ->
                            if (generation.get() == request) trySend(WatchLink.UNKNOWN)
                            logFailure(TAG, "watch_reachability_check_failed", error)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    if (generation.get() == request) trySend(WatchLink.UNKNOWN)
                    logFailure(TAG, "watch_capability_check_failed", error)
                }
        }

        val listener = CapabilityClient.OnCapabilityChangedListener { info ->
            // Capability callbacks contain reachable nodes; refresh all nodes to distinguish
            // an absent watch app from an installed companion that is currently disconnected.
            refresh(reachableNow = info.nodes.isNotEmpty())
        }
        client.addListener(listener, WATCH_CONTROL_CAPABILITY)
            .addOnFailureListener { error ->
                logFailure(TAG, "watch_capability_listener_failed", error)
            }
        refresh()

        awaitClose {
            client.removeListener(listener, WATCH_CONTROL_CAPABILITY)
                .addOnFailureListener { error ->
                    logFailure(TAG, "watch_capability_listener_remove_failed", error)
                }
        }
    }

    private const val TAG = "WatchConnectivity"
    private const val WATCH_CONTROL_CAPABILITY = "aidailymemory_recording_controller"
}
