/*
 * Copyright (c) 2025-2026 Meshtastic LLC
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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.map

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.plugin.gestures.generated.GesturesSettings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.common.util.nowSeconds
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.map
import org.meshtastic.core.ui.component.MainAppBar
import org.meshtastic.feature.map.component.EditWaypointDialog
import org.meshtastic.feature.map.component.MapControlsOverlay
import org.meshtastic.proto.Waypoint

/**
 * Unified Mapbox-backed main map screen. Replaces the former [LocalMapViewProvider] / [LocalMapMainScreenProvider]
 * indirection with a self-contained composable that lives entirely in `feature:map/androidMain`.
 *
 * Responsibilities:
 * - Scaffold with [MainAppBar]
 * - [MapboxMap] with persisted camera via [MapViewModel]
 * - Location permissions + tracking via FusedLocationProviderClient (through [MapboxMap] built-in MyLocation layer)
 * - Node cluster markers, precision circles, waypoint markers via [MapboxMapContent] helpers
 * - Long-press to create/edit waypoints via [EditWaypointDialog]
 * - [MapControlsOverlay] toolbar (filter, compass, location toggle)
 * - Waypoint deep-link: animates camera to [waypointId] on first composition
 */
@OptIn(ExperimentalPermissionsApi::class, MapboxExperimental::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun MapScreen(
    onClickNodeChip: (Int) -> Unit,
    navigateToNodeDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SharedMapViewModel = koinViewModel(),
    mapViewModel: MapViewModel = koinViewModel(),
    waypointId: Int? = null,
) {
    val ourNodeInfo by viewModel.ourNodeInfo.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()

    // --- Location permissions ---
    val locationPermissionsState =
        rememberMultiplePermissionsState(permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    var triggerLocationToggleAfterPermission by remember { mutableStateOf(false) }
    var isLocationTrackingEnabled by remember { mutableStateOf(false) }
    var followPhoneBearing by remember { mutableStateOf(false) }

    LaunchedEffect(locationPermissionsState.allPermissionsGranted) {
        if (locationPermissionsState.allPermissionsGranted && triggerLocationToggleAfterPermission) {
            isLocationTrackingEnabled = true
            triggerLocationToggleAfterPermission = false
        }
    }

    // --- Camera state ---
    val viewportState = rememberMapViewportState {
        setCameraOptions {
            val opts = mapViewModel.initialCameraOptions()
            center(opts.center ?: Point.fromLngLat(0.0, 0.0))
            zoom(opts.zoom ?: 5.0)
            bearing(opts.bearing ?: 0.0)
            pitch(opts.pitch ?: 0.0)
        }
    }

    // Persist camera when viewport stops moving
    LaunchedEffect(viewportState.mapViewportStatus) {
        val cam = viewportState.cameraState ?: return@LaunchedEffect
        val center = cam.center
        mapViewModel.saveCameraPosition(
            lat = center.latitude(),
            lng = center.longitude(),
            zoom = cam.zoom,
            bearing = cam.bearing,
            pitch = cam.pitch,
        )
    }

    // --- Node / waypoint data ---
    val allNodes by viewModel.nodesWithPosition.collectAsStateWithLifecycle(listOf())
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle(emptyMap())
    val mapFilterState by viewModel.mapFilterStateFlow.collectAsStateWithLifecycle()
    val selectedWaypointId by mapViewModel.selectedWaypointId.collectAsStateWithLifecycle()
    val selectedMapStyle by mapViewModel.selectedMapStyle.collectAsStateWithLifecycle()

    // Handle incoming waypointId deep-link: store it in the ViewModel and animate to it once
    LaunchedEffect(waypointId) {
        if (waypointId != null) {
            mapViewModel.setWaypointId(waypointId)
        }
    }

    // Animate camera to selected waypoint
    LaunchedEffect(selectedWaypointId, waypoints) {
        val id = selectedWaypointId ?: return@LaunchedEffect
        val wpt = waypoints.values.mapNotNull { it.waypoint }.find { it.id == id } ?: return@LaunchedEffect
        val lat = (wpt.latitude_i ?: 0) * COORDINATE_SCALE
        val lng = (wpt.longitude_i ?: 0) * COORDINATE_SCALE
        if (lat != 0.0 || lng != 0.0) {
            viewportState.flyTo(
                com.mapbox.maps.CameraOptions.Builder().center(Point.fromLngLat(lng, lat)).zoom(14.0).build(),
            )
        }
    }

    val filteredNodes =
        remember(allNodes, mapFilterState, ourNodeInfo) {
            allNodes
                .filter { node -> !mapFilterState.onlyFavorites || node.isFavorite || node.num == ourNodeInfo?.num }
                .filter { node ->
                    mapFilterState.lastHeardFilter.seconds == 0L ||
                        (nowSeconds - node.lastHeard) <= mapFilterState.lastHeardFilter.seconds ||
                        node.num == ourNodeInfo?.num
                }
        }

    // --- Waypoint editing state ---
    var editingWaypoint by remember { mutableStateOf<Waypoint?>(null) }

    // --- Filter menu expanded state ---
    var mapFilterMenuExpanded by remember { mutableStateOf(false) }

    // --- Bearing for compass (read from viewport camera) ---
    val bearing = (viewportState.cameraState?.bearing ?: 0.0).toFloat()

    val coroutineScope = rememberCoroutineScope()

    val mapState = rememberMapState {
        gesturesSettings = GesturesSettings {
            rotateEnabled = true
            scrollEnabled = true
            pitchEnabled = true
            doubleTapToZoomInEnabled = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            MainAppBar(
                title = stringResource(Res.string.map),
                ourNode = ourNodeInfo,
                showNodeChip = ourNodeInfo != null && isConnected,
                canNavigateUp = false,
                onNavigateUp = {},
                actions = {},
                onClickChip = { onClickNodeChip(it.num) },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = viewportState,
                mapState = mapState,
                style = { MapStyle(style = selectedMapStyle.styleUri) },
                onMapLongClickListener = { point ->
                    if (isConnected) {
                        editingWaypoint =
                            Waypoint(
                                latitude_i = (point.latitude() / DEG_D).toInt(),
                                longitude_i = (point.longitude() / DEG_D).toInt(),
                            )
                    }
                    false
                },
            ) {
                NodeClusterMarkers(nodes = filteredNodes, onNodeClick = { node -> navigateToNodeDetails(node.num) })
                PrecisionCircles(nodes = filteredNodes)
                WaypointMarkers(
                    waypoints = waypoints.values,
                    filterState = mapFilterState,
                    selectedWaypointId = selectedWaypointId,
                    onWaypointClick = { wpt -> editingWaypoint = wpt },
                )
            }

            // Controls overlay
            MapControlsOverlay(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                bearing = bearing,
                onToggleFilterMenu = { mapFilterMenuExpanded = true },
                filterDropdownContent = {},
                isLocationTrackingEnabled = isLocationTrackingEnabled,
                onToggleLocationTracking = {
                    if (locationPermissionsState.allPermissionsGranted) {
                        isLocationTrackingEnabled = !isLocationTrackingEnabled
                        if (!isLocationTrackingEnabled) followPhoneBearing = false
                    } else {
                        triggerLocationToggleAfterPermission = true
                        locationPermissionsState.launchMultiplePermissionRequest()
                    }
                },
                followPhoneBearing = followPhoneBearing,
                onCompassClick = {
                    if (isLocationTrackingEnabled) {
                        followPhoneBearing = !followPhoneBearing
                    } else {
                        coroutineScope.launch {
                            viewportState.flyTo(com.mapbox.maps.CameraOptions.Builder().bearing(0.0).build())
                        }
                    }
                },
            )

            // Waypoint edit dialog
            editingWaypoint?.let { waypointToEdit ->
                EditWaypointDialog(
                    waypoint = waypointToEdit,
                    onSendClicked = { updatedWp ->
                        mapViewModel.createAndSendWaypoint(
                            existing = if (updatedWp.id != 0) updatedWp else null,
                            name = updatedWp.name ?: "",
                            description = updatedWp.description ?: "",
                            icon = updatedWp.icon ?: 0,
                            lat = (updatedWp.latitude_i ?: 0) * COORDINATE_SCALE,
                            lng = (updatedWp.longitude_i ?: 0) * COORDINATE_SCALE,
                        )
                        editingWaypoint = null
                    },
                    onDeleteClicked = { wpToDelete ->
                        if ((wpToDelete.locked_to ?: 0) == 0 && isConnected && wpToDelete.id != 0) {
                            viewModel.sendWaypoint(wpToDelete.copy(expire = 1))
                        }
                        viewModel.deleteWaypoint(wpToDelete.id)
                        editingWaypoint = null
                    },
                    onDismissRequest = { editingWaypoint = null },
                )
            }
        }
    }
}
