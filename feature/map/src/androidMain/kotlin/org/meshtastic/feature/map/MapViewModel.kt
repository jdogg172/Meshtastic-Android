/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.map

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.RadioController
import org.meshtastic.core.repository.MapPrefs
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.map.model.MapStyle
import org.meshtastic.proto.Waypoint

/**
 * Android-specific extension of [BaseMapViewModel] that adds:
 * - Mapbox camera state persistence (lat/lng/zoom/bearing/pitch) via DataStore
 * - Map style selection
 * - Waypoint creation helper
 *
 * The DataStore is created eagerly in the ViewModel's own scope to avoid requiring an activity-scoped store; it mirrors
 * the pattern used by the former GoogleMapsPrefsImpl.
 */
@KoinViewModel
class MapViewModel(
    context: Context,
    mapPrefs: MapPrefs,
    nodeRepository: NodeRepository,
    packetRepository: PacketRepository,
    radioController: RadioController,
    private val dispatchers: CoroutineDispatchers,
) : BaseMapViewModel(mapPrefs, nodeRepository, packetRepository, radioController) {

    private val prefsScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = prefsScope,
            produceFile = { context.preferencesDataStoreFile("mapbox_map_prefs") },
        )

    // ---- Camera prefs ----

    val cameraLat: StateFlow<Double> =
        dataStore.data.map { it[KEY_CAMERA_LAT] ?: 0.0 }.stateIn(prefsScope, SharingStarted.Eagerly, 0.0)

    val cameraLng: StateFlow<Double> =
        dataStore.data.map { it[KEY_CAMERA_LNG] ?: 0.0 }.stateIn(prefsScope, SharingStarted.Eagerly, 0.0)

    val cameraZoom: StateFlow<Double> =
        dataStore.data.map { it[KEY_CAMERA_ZOOM] ?: 5.0 }.stateIn(prefsScope, SharingStarted.Eagerly, 5.0)

    val cameraBearing: StateFlow<Double> =
        dataStore.data.map { it[KEY_CAMERA_BEARING] ?: 0.0 }.stateIn(prefsScope, SharingStarted.Eagerly, 0.0)

    val cameraPitch: StateFlow<Double> =
        dataStore.data.map { it[KEY_CAMERA_PITCH] ?: 0.0 }.stateIn(prefsScope, SharingStarted.Eagerly, 0.0)

    /** Builds the initial [CameraOptions] from persisted prefs. */
    fun initialCameraOptions(): CameraOptions = CameraOptions.Builder()
        .center(Point.fromLngLat(cameraLng.value, cameraLat.value))
        .zoom(cameraZoom.value)
        .bearing(cameraBearing.value)
        .pitch(cameraPitch.value)
        .build()

    fun saveCameraPosition(lat: Double, lng: Double, zoom: Double, bearing: Double, pitch: Double) {
        prefsScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_CAMERA_LAT] = lat
                prefs[KEY_CAMERA_LNG] = lng
                prefs[KEY_CAMERA_ZOOM] = zoom
                prefs[KEY_CAMERA_BEARING] = bearing
                prefs[KEY_CAMERA_PITCH] = pitch
            }
        }
    }

    // ---- Selected waypoint (for deep-link animation) ----

    private val _selectedWaypointId = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val selectedWaypointId: StateFlow<Int?> = _selectedWaypointId.stateInWhileSubscribed(initialValue = null)

    fun setWaypointId(id: Int?) {
        _selectedWaypointId.value = id
    }

    // ---- Map style ----

    val selectedMapStyle: StateFlow<MapStyle> =
        dataStore.data
            .map { prefs ->
                val name = prefs[KEY_MAP_STYLE]
                MapStyle.entries.find { it.name == name } ?: MapStyle.Standard
            }
            .stateIn(prefsScope, SharingStarted.Eagerly, MapStyle.Standard)

    fun setMapStyle(style: MapStyle) {
        prefsScope.launch { dataStore.edit { it[KEY_MAP_STYLE] = style.name } }
    }

    // ---- Waypoint creation ----

    /**
     * Constructs a new or edited [Waypoint] and broadcasts it over the mesh.
     * - New waypoints (id == 0) get a fresh packet-id assigned.
     * - Locked waypoints are silently dropped.
     * - Falls back to (0,0) if [lat]/[lng] are null.
     */
    fun createAndSendWaypoint(
        existing: Waypoint?,
        name: String,
        description: String,
        icon: Int,
        lat: Double?,
        lng: Double?,
    ) {
        if (existing != null && (existing.locked_to ?: 0) != 0) return

        val latI = ((lat ?: 0.0) / COORDINATE_SCALE).toInt()
        val lngI = ((lng ?: 0.0) / COORDINATE_SCALE).toInt()

        val waypoint =
            Waypoint(
                id = existing?.id ?: (generatePacketId() ?: 0),
                name = name,
                description = description,
                icon = if (icon == 0) 0x1F4CD else icon,
                latitude_i = latI,
                longitude_i = lngI,
                locked_to = existing?.locked_to ?: 0,
                expire = existing?.expire ?: 0,
            )
        sendWaypoint(waypoint, "0${DataPacket.ID_BROADCAST}")
    }

    companion object {
        private val KEY_CAMERA_LAT = doublePreferencesKey("mapbox_camera_lat")
        private val KEY_CAMERA_LNG = doublePreferencesKey("mapbox_camera_lng")
        private val KEY_CAMERA_ZOOM = doublePreferencesKey("mapbox_camera_zoom")
        private val KEY_CAMERA_BEARING = doublePreferencesKey("mapbox_camera_bearing")
        private val KEY_CAMERA_PITCH = doublePreferencesKey("mapbox_camera_pitch")
        private val KEY_MAP_STYLE = stringPreferencesKey("mapbox_map_style")

        @Suppress("unused")
        private val KEY_CAMERA_ZOOM_FLOAT = floatPreferencesKey("mapbox_camera_zoom_legacy")
    }
}
