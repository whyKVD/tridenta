package org.stypox.tridenta.widget.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.Visibility
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.visibility
import org.stypox.tridenta.R
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.CardinalPoint
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.ui.MainActivity
import org.stypox.tridenta.util.formatDateFull
import org.stypox.tridenta.util.textColorOnBackground
import org.stypox.tridenta.util.toLineColor
import java.time.OffsetDateTime
import java.time.ZoneOffset

fun formatDurationMinutes(context: Context, minutes: Int): String {
    return context.getString(R.string.short_minute_format, minutes)
}

@Composable
fun TripViewGlance(
    trip: UiTrip?,
    error: Boolean,
    loading: Boolean,
    onReloadAction: Action,
    onPrevAction: Action,
    onNextAction: Action,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    stopIdToHighlight: Int?,
    stopTypeToHighlight: StopLineType?,
    modifier: GlanceModifier = GlanceModifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (trip != null) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TripViewTopRowGlance(
                    trip = trip,
                    modifier = GlanceModifier.padding(
                        start = 12.dp,
                        top = 4.dp,
                        end = 12.dp,
                        bottom = 12.dp
                    ).fillMaxWidth()
                )

                if (error) {
                    // Replaced your custom ErrorRow with a simple Glance Text for the widget
                    Text(
                        text = context.getString(R.string.error),
                        style = TextStyle(color = GlanceTheme.colors.error),
                        modifier = GlanceModifier.padding(8.dp)
                    )
                }

                // Assuming this is already refactored to be Glance-compliant!
                TripViewStopsGlance(
                    trip = trip,
                    stopIdToHighlight = stopIdToHighlight,
                    stopTypeToHighlight = stopTypeToHighlight,
                    modifier = GlanceModifier.defaultWeight() // Crucial for lists in Columns
                )
            }

        } else if (loading) {
            CircularProgressIndicator()

        } else if (error) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = context.getString(R.string.error),
                    style = TextStyle(color = GlanceTheme.colors.error)
                )
                Button(text = context.getString(R.string.reload), onClick = onReloadAction)
            }

        } else {
            Column(
                modifier = GlanceModifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = context.getString(R.string.no_trip_found),
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onBackground
                    ),
                    modifier = GlanceModifier.padding(bottom = 4.dp)
                )
                Text(
                    text = context.getString(R.string.no_trip_found_description),
                    style = TextStyle(
                        textAlign = TextAlign.Center,
                        color = GlanceTheme.colors.onBackground
                    )
                )
            }
        }

        // Bottom Row is aligned to the bottom using a Box setup
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            TripViewBottomRowGlance(
                loading = loading,
                onReloadAction = onReloadAction,
                onPrevAction = onPrevAction,
                onNextAction = onNextAction,
                prevEnabled = prevEnabled,
                nextEnabled = nextEnabled,
                modifier = GlanceModifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun StopLineTypeIcon(stopLineType: StopLineType,context: Context, modifier: GlanceModifier = GlanceModifier) {
    Image(
        provider = ImageProvider(
            when (stopLineType) {
                StopLineType.Urban -> R.drawable.location_city
                StopLineType.Suburban -> R.drawable.landscape
            }
        ),
        contentDescription = context.getString(
            when (stopLineType) {
                StopLineType.Urban -> R.string.urban
                StopLineType.Suburban -> R.string.suburban
            }
        ),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        modifier = modifier,
    )
}
@Composable
fun DirectionIconGlance(
    direction: Direction,
    context: Context,
    modifier: GlanceModifier = GlanceModifier
) {
    Image(
        provider = ImageProvider(
            when (direction) {
                Direction.Forward -> R.drawable.turn_sharp_right
                Direction.Backward -> R.drawable.u_turn_left
                Direction.ForwardAndBackward -> R.drawable.swap_calls
            }
        ),
        contentDescription = context.getString(
            when (direction) {
                Direction.Forward -> R.string.forward
                Direction.Backward -> R.string.backward
                Direction.ForwardAndBackward -> R.string.forward_and_backward
            }
        ),
        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        modifier = modifier
    )
}

@Composable
private fun TripViewTopRowGlance(
    trip: UiTrip,
    modifier: GlanceModifier = GlanceModifier
) {
    val context = LocalContext.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        if (trip.line != null) {
            // Replaced Surface with Box + background
            val shortNameBackground = trip.line.color.toLineColor()
            val textColor = textColorOnBackground(shortNameBackground)
            Box(
                modifier = GlanceModifier
                    .background(shortNameBackground)
                    .padding(8.dp)
            ) {
                Text(
                    text = trip.line.shortName,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(day = textColor, night = textColor),
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Column(
            modifier = GlanceModifier.defaultWeight().padding(horizontal = 12.dp)
        ) {
            Text(
                text = trip.headSign,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontWeight = FontWeight.Medium
                )
            )

            val dateOrDelayText = if (trip.lastEventReceivedAt == null) {
                trip.stopTimes.asSequence()
                    .map { it.arrivalTime }
                    .filterNotNull()
                    .firstOrNull()
                    ?.let { firstArrival -> formatDateFull(firstArrival) }
                    ?: context.getString(R.string.no_date_time_information)
            } else {
                if (trip.delay < 0)
                    context.getString(R.string.early, formatDurationMinutes(context, -trip.delay))
                else if (trip.delay == 0)
                    context.getString(R.string.on_time)
                else
                    context.getString(R.string.late, formatDurationMinutes(context, trip.delay))
            }
            Text(
                text = dateOrDelayText,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
            )
        }

        Column {
            StopLineTypeIcon(trip.type,context)
            DirectionIconGlance(trip.direction, context)
        }
    }
}

@Composable
private fun TripViewBottomRowGlance(
    loading: Boolean,
    onReloadAction: Action,
    onPrevAction: Action,
    onNextAction: Action,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    modifier: GlanceModifier = GlanceModifier
) {
    val context = LocalContext.current
    val buttonModifier = GlanceModifier.size(48.dp)
        .cornerRadius(12.dp)
        .background(GlanceTheme.colors.primary)

    // Widgets don't support FloatingActionButtons. Use standard Buttons or Images.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (prevEnabled) buttonModifier.clickable(onPrevAction) else buttonModifier.visibility(
                Visibility.Invisible
            )
        ) {
            Image(
                provider = ImageProvider(R.drawable.arrow_left),
                contentDescription = context.getString(R.string.previous),
                modifier = GlanceModifier
                    .size(32.dp)
            )
        }
        Spacer(GlanceModifier.defaultWeight())


        Box(
            contentAlignment = Alignment.Center,
            modifier = buttonModifier.clickable(onReloadAction)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = GlanceModifier.defaultWeight()
                        .size(24.dp)
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.refresh),
                    contentDescription = context.getString(R.string.reload),
                    modifier = GlanceModifier
                        .size(24.dp)
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())

        Box(
            contentAlignment = Alignment.Center,
            modifier = if (nextEnabled) buttonModifier.clickable(onNextAction) else buttonModifier.visibility(
                Visibility.Invisible
            )
        ) {
            Image(
                provider = ImageProvider(R.drawable.arrow_right),
                contentDescription = context.getString(R.string.next),
                GlanceModifier.size(32.dp)
            )
        }
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview
@Composable
private fun TripViewPreview() {
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
        TripViewGlance(
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
            error = false,
            loading = false,
            onReloadAction = actionStartActivity<MainActivity>(),
            onPrevAction = actionStartActivity<MainActivity>(),
            onNextAction = actionStartActivity<MainActivity>(),
            prevEnabled = true,
            nextEnabled = true,
            stopIdToHighlight = null,
            stopTypeToHighlight = null,
        )
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview
@Composable
private fun TripViewPreviewLoading() {
    val loading = true
    GlanceTheme {
        TripViewGlance(
            trip = null,
            error = false,
            loading = loading,
            onReloadAction = actionStartActivity<MainActivity>(),
            onPrevAction = actionStartActivity<MainActivity>(),
            onNextAction = actionStartActivity<MainActivity>(),
            prevEnabled = true,
            nextEnabled = true,
            stopIdToHighlight = null,
            stopTypeToHighlight = null,
        )
    }
}