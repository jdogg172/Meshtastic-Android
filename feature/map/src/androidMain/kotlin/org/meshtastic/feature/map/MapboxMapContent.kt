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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotationGroup
import com.mapbox.maps.plugin.annotation.AnnotationConfig
import com.mapbox.maps.plugin.annotation.AnnotationSourceOptions
import com.mapbox.maps.plugin.annotation.ClusterOptions
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.util.GeoConstants.DEG_D
import org.meshtastic.feature.map.BaseMapViewModel.MapFilterState
import org.meshtastic.proto.Position
import org.meshtastic.proto.Waypoint

// ---------------------------------------------------------------------------
// Node cluster markers
// ---------------------------------------------------------------------------

/**
 * Renders node markers with Mapbox clustering.
 *
 * Each node is a [PointAnnotationOptions] coloured by [Node.colors]. Clusters are rendered as [CircleAnnotationOptions]
 * sized by point count.
 */
@Composable
internal fun NodeClusterMarkers(nodes: List<Node>, onNodeClick: (Node) -> Unit) {
    if (nodes.isEmpty()) return

    PointAnnotationGroup(
        annotations =
        nodes.map { node ->
            val lat = (node.position?.latitude_i ?: 0) * DEG_D
            val lng = (node.position?.longitude_i ?: 0) * DEG_D
            PointAnnotationOptions()
                .withPoint(Point.fromLngLat(lng, lat))
                .withTextField(node.user?.short_name ?: "?")
                .withTextColor(Color.WHITE)
                .withTextSize(12.0)
                .withIconSize(1.0)
        },
        annotationConfig =
        AnnotationConfig(
            annotationSourceOptions =
            AnnotationSourceOptions(
                clusterOptions =
                ClusterOptions(
                    circleRadiusExpression =
                    com.mapbox.maps.extension.style.expressions.dsl.generated.literal(18.0),
                    colorLevels =
                    listOf(
                        Pair(10, Color.RED),
                        Pair(5, Color.parseColor("#FF8800")),
                        Pair(0, Color.parseColor("#2196F3")),
                    ),
                    textColor = Color.WHITE,
                    textSize = 14.0,
                ),
            ),
        ),
    ) {
        interactionsState.onClicked { annotation ->
            val nodeNum =
                annotation.point.let { pt ->
                    nodes.minByOrNull { n ->
                        val lat = (n.position?.latitude_i ?: 0) * DEG_D
                        val lng = (n.position?.longitude_i ?: 0) * DEG_D
                        val dLat = lat - pt.latitude()
                        val dLng = lng - pt.longitude()
                        dLat * dLat + dLng * dLng
                    }
                }
            nodeNum?.let { onNodeClick(it) }
            true
        }
    }
}

// ---------------------------------------------------------------------------
// Precision circles
// ---------------------------------------------------------------------------

/**
 * Renders a translucent precision circle for each node that has [precisionBitsToMeters] > 0. Zoom-level-accurate radius
 * requires the native MapEffect workaround; for this POC we render fixed-radius circles and note that per-zoom
 * interpolation is deferred.
 */
@Composable
internal fun PrecisionCircles(nodes: List<Node>) {
    val circleNodes = nodes.filter { n -> precisionBitsToMeters(n.position?.precision_bits ?: 0) > 0 }
    if (circleNodes.isEmpty()) return

    CircleAnnotationGroup(
        annotations =
        circleNodes.map { node ->
            val lat = (node.position?.latitude_i ?: 0) * DEG_D
            val lng = (node.position?.longitude_i ?: 0) * DEG_D
            val colorInt = node.colors.second
            CircleAnnotationOptions()
                .withPoint(Point.fromLngLat(lng, lat))
                // Radius in pixels — approximate at zoom 15; proper meter conversion deferred
                .withCircleRadius(20.0)
                .withCircleColor(colorInt and 0xFFFFFF or (0x33 shl 24)) // 20% opacity
                .withCircleStrokeColor(colorInt)
                .withCircleStrokeWidth(1.5)
        },
    )
}

// ---------------------------------------------------------------------------
// Waypoint markers
// ---------------------------------------------------------------------------

@Composable
internal fun WaypointMarkers(
    waypoints: Collection<DataPacket>,
    filterState: MapFilterState,
    selectedWaypointId: Int?,
    onWaypointClick: (Waypoint) -> Unit,
) {
    if (!filterState.showWaypoints) return
    val displayable = waypoints.mapNotNull { it.waypoint }
    if (displayable.isEmpty()) return

    PointAnnotationGroup(
        annotations =
        displayable.map { wpt ->
            val lat = (wpt.latitude_i ?: 0) * COORDINATE_SCALE
            val lng = (wpt.longitude_i ?: 0) * COORDINATE_SCALE
            PointAnnotationOptions()
                .withPoint(Point.fromLngLat(lng, lat))
                .withTextField(convertIntToEmoji(wpt.icon ?: 0x1F4CD))
                .withTextSize(if (wpt.id == selectedWaypointId) 28.0 else 20.0)
        },
    ) {
        interactionsState.onClicked { annotation ->
            val clicked =
                displayable.minByOrNull { wpt ->
                    val lat = (wpt.latitude_i ?: 0) * COORDINATE_SCALE
                    val lng = (wpt.longitude_i ?: 0) * COORDINATE_SCALE
                    val dLat = lat - annotation.point.latitude()
                    val dLng = lng - annotation.point.longitude()
                    dLat * dLat + dLng * dLng
                }
            clicked?.let { onWaypointClick(it) }
            true
        }
    }
}

// ---------------------------------------------------------------------------
// Track polyline
// ---------------------------------------------------------------------------

@Composable
internal fun TrackPolyline(positions: List<Position>, colorInt: Int) {
    if (positions.size < 2) return
    val points = positions.mapNotNull { it.toPointOrNull() }
    if (points.size < 2) return

    PolylineAnnotationGroup(
        annotations =
        listOf(
            PolylineAnnotationOptions()
                .withPoints(points)
                .withLineColor(colorInt)
                .withLineWidth(3.0)
                .withLineOpacity(0.85),
        ),
    )
}

// ---------------------------------------------------------------------------
// Traceroute polylines (offset forward + return routes)
// ---------------------------------------------------------------------------

@Composable
internal fun TraceroutePolylines(forwardPoints: List<Point>, returnPoints: List<Point>) {
    if (forwardPoints.size >= 2) {
        PolylineAnnotationGroup(
            annotations =
            listOf(
                PolylineAnnotationOptions()
                    .withPoints(forwardPoints)
                    .withLineColor(Color.parseColor("#2196F3")) // blue — outgoing
                    .withLineWidth(4.0)
                    .withLineOpacity(0.9),
            ),
        )
    }
    if (returnPoints.size >= 2) {
        PolylineAnnotationGroup(
            annotations =
            listOf(
                PolylineAnnotationOptions()
                    .withPoints(returnPoints)
                    .withLineColor(Color.parseColor("#FF5722")) // deep orange — return
                    .withLineWidth(3.0)
                    .withLineOpacity(0.9),
            ),
        )
    }
}
