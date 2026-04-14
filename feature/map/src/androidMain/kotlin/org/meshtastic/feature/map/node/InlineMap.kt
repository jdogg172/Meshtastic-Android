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

package org.meshtastic.feature.map.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.MapStyle
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.gestures.generated.GesturesSettings
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.feature.map.precisionBitsToMeters

private const val DEFAULT_ZOOM = 15.0

/**
 * Read-only embedded Mapbox map for the node detail screen. Shows a node chip marker and optional precision circle. No
 * controls or interaction.
 */
@OptIn(MapboxExperimental::class)
@Composable
fun InlineMap(node: Node, modifier: Modifier = Modifier) {
    key(node.num) {
        val lat = (node.position?.latitude_i ?: 0) * DEG_D
        val lng = (node.position?.longitude_i ?: 0) * DEG_D
        val viewportState = rememberMapViewportState {
            setCameraOptions {
                center(Point.fromLngLat(lng, lat))
                zoom(DEFAULT_ZOOM)
            }
        }
        val mapState = rememberMapState {
            gesturesSettings = GesturesSettings {
                rotateEnabled = false
                scrollEnabled = false
                pitchEnabled = false
                doubleTapToZoomInEnabled = false
                quickZoomEnabled = false
                pinchToZoomEnabled = false
            }
        }

        MapboxMap(
            modifier = modifier,
            mapViewportState = viewportState,
            mapState = mapState,
            style = { MapStyle(style = com.mapbox.maps.Style.STANDARD) },
        ) {
            val precisionMeters = precisionBitsToMeters(node.position?.precision_bits ?: 0)
            val colorInt = node.colors.second

            if (precisionMeters > 0) {
                CircleAnnotationGroup(
                    annotations =
                    listOf(
                        CircleAnnotationOptions()
                            .withPoint(Point.fromLngLat(lng, lat))
                            .withCircleRadius(precisionMeters.coerceAtMost(5000.0) / 10.0)
                            .withCircleColor(colorInt and 0xFFFFFF or (0x33 shl 24))
                            .withCircleStrokeColor(colorInt)
                            .withCircleStrokeWidth(1.5),
                    ),
                )
            }

            PointAnnotationGroup(
                annotations =
                listOf(
                    PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(lng, lat))
                        .withTextField(node.user?.short_name ?: "?")
                        .withTextColor(android.graphics.Color.WHITE)
                        .withTextSize(12.0),
                ),
            )
        }
    }
}
