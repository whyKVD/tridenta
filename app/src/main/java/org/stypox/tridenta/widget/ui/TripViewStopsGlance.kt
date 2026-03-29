package org.stypox.tridenta.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import org.stypox.tridenta.R
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.CardinalPoint
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.extractor.data.ExTrip
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.util.formatConcatStrings
import org.stypox.tridenta.util.formatTime
import org.stypox.tridenta.widget.actions.OnStopClickAction
import org.stypox.tridenta.widget.actions.WidgetKeys
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Composable
fun TripViewStopsGlance(
    trip: UiTrip,
    stopIdToHighlight: Int?,
    stopTypeToHighlight: StopLineType?,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        itemsIndexed(trip.stopTimes) { index, stopTime ->
            TripViewStopItemGlance(
                trip = trip,
                highlight = stopTime.stop != null &&
                        stopTime.stop.stopId == stopIdToHighlight &&
                        stopTime.stop.type == stopTypeToHighlight,
                completed = index < trip.completedStops,
                stopTime = stopTime,
                modifier = if (stopTime.stop == null) {
                    GlanceModifier // not clickable, since there is no stop
                } else {
                    GlanceModifier.clickable(
                        actionRunCallback<OnStopClickAction>(
                            actionParametersOf(
                                WidgetKeys.STOP_ID to stopTime.stop.stopId,
                                WidgetKeys.STOP_TYPE to stopTime.stop.type.name
                            )
                        )
                    )
                }
            )
        }

        item {
            Text(
                text = formatConcatStrings(
                    if (trip.lastEventReceivedAt == null) {
                        context.getString(R.string.no_update)
                    } else {
                        context.getString(
                            R.string.last_update,
                            formatTime(trip.lastEventReceivedAt)
                        )
                    },
                    if (trip.busId == ExTrip.BUS_ID_UNKNOWN) {
                        null
                    } else {
                        context.getString(R.string.bus_id, trip.busId)
                    }
                ),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface
                ),
                modifier = GlanceModifier.padding(8.dp)
            )
        }

        item {
            // space for FABs
            Spacer(modifier = GlanceModifier.size(height = 84.dp, width = 0.dp))
        }
    }
}

@Composable
private fun TripViewStopItemGlance(
    trip: UiTrip,
    highlight: Boolean,
    completed: Boolean,
    stopTime: UiStopTime,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 0.dp).fillMaxWidth()
    ) {
        Image(
            provider = ImageProvider(
                if (trip.lastEventReceivedAt == null) {
                    R.drawable.arrow_right
                } else if (completed) {
                    R.drawable.check_circle
                } else {
                    R.drawable.radio_button_unchecked
                }
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.padding(
                end = 6.dp,
            ),
        )

        if (stopTime.stop?.isFavorite == true) {
            Image(
                provider = ImageProvider(R.drawable.favorite_filled),
                contentDescription = context.getString(R.string.favorite),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.padding(end = 3.dp)
                    .height(16.dp),
            )
        }

        stopTime.stop?.cardinalPoint?.let {
            Text(
                text = context.getString(it.shortName),
                style = TextStyle(
                    color = GlanceTheme.colors.primary
                ),
                modifier = GlanceModifier.padding(end = 3.dp),
            )
        }

        val textColor = if (stopTime.stop == null) {
            GlanceTheme.colors.error
        } else if (highlight) {
            GlanceTheme.colors.primary
        } else {
            GlanceTheme.colors.onSurface
        }

        Text(
            text = stopTime.stop?.name ?: context.getString(R.string.error),
            maxLines = 1,
            style = TextStyle(
                fontWeight = if (highlight) FontWeight.Bold else null,
                color = textColor
            ),
            modifier = GlanceModifier.defaultWeight()
                .run {
                    if (stopTime.arrivalTime == null && stopTime.departureTime == null) {
                        this // do not apply end padding if there is nothing after
                    } else {
                        padding(end = 6.dp)
                    }
                },
        )

        val isLate = trip.lastEventReceivedAt != null
                && !completed
                && trip.delay > 0
        val lateDecoration = if (isLate) TextDecoration.LineThrough else null
        val highlightWeight = if (highlight) FontWeight.Bold else null

        if (stopTime.arrivalTime != null) {
            // TODO `key` forces recompositions when `lateDecoration` changes, needed because of
            //  probably a bug in Compose (also see below)
            Text(
                text = formatTime(stopTime.arrivalTime),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = highlightWeight,
                    textDecoration = lateDecoration
                ),
            )
        }
        if (stopTime.arrivalTime != stopTime.departureTime) {
            if (stopTime.arrivalTime != null) {
                Image(
                    provider = ImageProvider(R.drawable.double_arrow),
                    contentDescription = null,
                    modifier = GlanceModifier.size(8.dp),
                )
            }
            if (stopTime.departureTime != null) {
                Text(
                    text = formatTime(stopTime.departureTime),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = highlightWeight,
                        textDecoration = lateDecoration
                    )
                )
            }
        }
        if (isLate) {
            sequenceOf(stopTime.arrivalTime, stopTime.departureTime).firstOrNull { it != null }
                ?.let { time ->
                    Text(
                        text = formatTime(
                            time.plusMinutes(trip.delay.toLong())
                        ),
                        modifier = GlanceModifier.padding(start = 5.dp),
                        maxLines = 1,
                        style = TextStyle(
                            fontWeight = highlightWeight,
                            color = GlanceTheme.colors.error
                        ),
                    )
                }
        }
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 250, heightDp = 130)
@Composable
fun TripViewStopsPreview() {
    val sampleDbStops: List<DbStop> = listOf(
        DbStop(
            stopId = 0,
            latitude = 0.0,
            longitude = 0.0,
            name = "Sorni",
            street = "Sorni",
            town = "",
            type = StopLineType.Urban,
            wheelchairAccessible = false,
            cardinalPoint = CardinalPoint.North,
            isFavorite = false,
        ),
        DbStop(
            stopId = 1,
            latitude = 0.0,
            longitude = 0.0,
            name = "Funivia-Staz. di Monte-Sardagna lorem ipsum dolor sit amet",
            street = "Cembra - Via 4 Novembre - Dir.Cavalese lorem ipsum dolor sit",
            town = "Appiano sulla strada del vino",
            type = StopLineType.Suburban,
            wheelchairAccessible = true,
            cardinalPoint = null,
            isFavorite = true,
        ),
        DbStop(
            stopId = 2,
            latitude = 0.0,
            longitude = 0.0,
            name = "Pinè Bivio",
            street = "Civezzano-Loc.La Mochena",
            town = "Civezzano",
            type = StopLineType.Suburban,
            wheelchairAccessible = false,
            cardinalPoint = CardinalPoint.NorthWest,
            isFavorite = true,
        ),
        DbStop(
            stopId = 3,
            latitude = 0.0,
            longitude = 0.0,
            name = "Verona Big Center",
            street = "Verona \"Big Center\"",
            town = "Trento",
            type = StopLineType.Suburban,
            wheelchairAccessible = true,
            cardinalPoint = null,
            isFavorite = false,
        )
    )
    val sampleDbLines: Sequence<DbLine> = sequenceOf(
        DbLine(
            lineId = 396,
            type = StopLineType.Urban,
            area = Area.UrbanTrento,
            color = 0xc52720,
            longName = "Cortesano Gardolo P.Dante Villazzano 3",
            shortName = "3",
            isFavorite = false
        ),
        DbLine(
            lineId = 404,
            type = StopLineType.Urban,
            area = Area.UrbanTrento,
            color = 0x52332a,
            longName = "Centochiavi Piazza Dante Mattarello",
            shortName = "8",
            isFavorite = true
        ),
        DbLine(
            lineId = 466,
            type = StopLineType.Urban,
            area = Area.UrbanTrento,
            color = 0xbf6092,
            longName = "P.Dante Rosmini S.Rocco Povo Polo Soc.",
            shortName = "13",
            isFavorite = false
        ),
        DbLine(
            lineId = 415,
            type = StopLineType.Urban,
            area = Area.UrbanTrento,
            color = 0xe490b0,
            longName = "P.Dante Via Sanseverino Belvedere Ravina",
            shortName = "14",
            isFavorite = true
        ),
        DbLine(
            lineId = 109,
            type = StopLineType.Suburban,
            area = Area.Suburban1,
            color = null,
            longName = "Cavalese - Masi di Cavalese",
            shortName = "B109",
            isFavorite = true
        ),
        DbLine(
            lineId = 201,
            type = StopLineType.Suburban,
            area = Area.Suburban2,
            color = null,
            longName = "Trento-Vezzano-Sarche-Tione",
            shortName = "B201",
            isFavorite = false
        ),
    )
    val referenceDateTime =
        OffsetDateTime.of(2022, 9, 26, 9, 33, 17, 328943849, ZoneOffset.UTC)
    GlanceTheme {
        TripViewStopsGlance(
            trip = UiTrip(
                delay = 1,
                direction = Direction.Backward,
                lastEventReceivedAt = referenceDateTime.minusMinutes(3),
                lineId = sampleDbLines.first().lineId,
                line = sampleDbLines.first(),
                headSign = "Conci \"Villazzano 3\"",
                tripId = "0003726592022061120220911",
                type = StopLineType.Urban,
                completedStops = 2,
                stopTimes = sampleDbStops.mapIndexed { index, dbStop ->
                    UiStopTime(
                        arrivalTime = referenceDateTime.plusMinutes((index - 2).toLong()),
                        departureTime = referenceDateTime.plusMinutes((index + index % 2 - 2).toLong()),
                        stop = dbStop
                    )
                },
                busId = 886,
            ),
            stopIdToHighlight = null,
            stopTypeToHighlight = null,
        )
    }
}