package com.skyglass.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Observer location via the framework [LocationManager] (no Play Services dependency).
 * Uses GPS when precise location is granted; approximate location also works, since 1° of
 * latitude error shifts the sky by only 1°.
 */
class LocationSource(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    /** Emits the freshest last-known fix immediately, then live updates while collected. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun updates(): Flow<Location> = callbackFlow {
        val providers = usableProviders()

        providers
            .mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.let { trySend(it) }

        // Balanced quality never powers up GPS, and outdoors at night there is often no Wi-Fi or
        // cell fix to fall back on. Updates are throttled and stop when the screen pauses.
        val quality = if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            LocationRequestCompat.QUALITY_HIGH_ACCURACY
        } else {
            LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequestCompat.Builder(UPDATE_INTERVAL_MILLIS)
            .setQuality(quality)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .build()
        // One listener per provider: registering the same listener again replaces its earlier request.
        val listeners = providers.map { provider ->
            LocationListenerCompat { trySend(it) }.also { listener ->
                LocationManagerCompat.requestLocationUpdates(
                    locationManager, provider, request, ContextCompat.getMainExecutor(context), listener,
                )
            }
        }

        awaitClose { listeners.forEach { LocationManagerCompat.removeUpdates(locationManager, it) } }
    }

    /**
     * All enabled providers we may use. GPS is requested directly rather than trusted to the fused
     * provider, which does not always power it up (e.g. depending on Play services settings).
     */
    private fun usableProviders(): List<String> {
        val fine = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return candidates.filter { locationManager.allProviders.contains(it) && locationManager.isProviderEnabled(it) }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 60_000L
        const val MIN_UPDATE_DISTANCE_METERS = 1_000f
    }
}
