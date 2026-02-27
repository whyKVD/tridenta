package org.stypox.tridenta.widget

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stypox.tridenta.R
import org.stypox.tridenta.db.LineDao
import org.stypox.tridenta.db.StopDao
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.CardinalPoint
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.extractor.data.ExTrip
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.sample.SampleUiTripProvider
import org.stypox.tridenta.ui.theme.LabelText
import org.stypox.tridenta.ui.trip.TripViewStops
import org.stypox.tridenta.util.formatConcatStrings
import org.stypox.tridenta.util.formatTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MyAppWidget(
    private val stopDao: StopDao,
    private val lineDao: LineDao,
    private val linesRepository: LinesRepository,
    private val tripsRepository: LineTripsRepository
) : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        // In this method, load data needed to render the AppWidget.
        // Use `withContext` to switch to another thread for long running
        // operations.
        lateinit var favoriteLines: List<DbLine>
        try {
            withContext(Dispatchers.IO) {
                val lines = lineDao.getAllLines();
                favoriteLines = lines.filter { l -> l.isFavorite }
                val line = favoriteLines[0]
                tripsRepository.getUiTrip(
                    line.lineId,
                    line.type,
                    ZonedDateTime.now(),
                    Direction.ForwardAndBackward
                )

            }
        } catch (e: Exception) {
            logError(e.message!!, e.cause)
        }

        provideContent {
            MyContent()
        }
    }
}

@Composable
fun MyContent() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text("Trips -")
            Spacer()
            Text(">  ")
        }
        Image(
            provider = ImageProvider(R.drawable.radio_button_unchecked),
            contentDescription = null,
        )
    }
}

@Composable
fun TripViewStops(
    trip: UiTrip,
    stopIdToHighlight: Int?,
    stopTypeToHighlight: StopLineType?,
    modifier: GlanceModifier = GlanceModifier,
    onStopClick: ((DbStop) -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(trip.stopTimes) { index, stopTime ->
            TripViewStopItem(
                trip = trip,
                highlight = stopTime.stop != null &&
                        stopTime.stop.stopId == stopIdToHighlight &&
                        stopTime.stop.type == stopTypeToHighlight,
                completed = index < trip.completedStops,
                stopTime = stopTime,
                modifier = if (stopTime.stop == null || onStopClick == null) {
                    GlanceModifier // not clickable, since there is no stop
                } else {
                    GlanceModifier.clickable { onStopClick(stopTime.stop) }
                }
            )
        }

        item {
            LabelText(
                text = formatConcatStrings(
                    if (trip.lastEventReceivedAt == null) {
                        stringResource(R.string.no_update)
                    } else {
                        stringResource(R.string.last_update, formatTime(trip.lastEventReceivedAt))
                    },
                    if (trip.busId == ExTrip.BUS_ID_UNKNOWN) {
                        null
                    } else {
                        stringResource(R.string.bus_id, trip.busId)
                    }
                ),
                modifier = Modifier.padding(8.dp)
            )
        }

        item {
            // space for FABs
            Spacer(modifier = GlanceModifier.size(height = 84.dp, width = 0.dp))
        }
    }
}

@Composable
private fun TripViewStopItem(
    trip: UiTrip,
    highlight: Boolean,
    completed: Boolean,
    stopTime: UiStopTime,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 0.dp)
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
            //tint = MaterialTheme.colorScheme.primary, TODO: Correct the color
            modifier = GlanceModifier.padding(
                end = 6.dp,
            ),
        )

        if (stopTime.stop?.isFavorite == true) {
            Image(
                provider = ImageProvider(R.drawable.favorite),
                contentDescription = stringResource(R.string.favorite),
                //tint = MaterialTheme.colorScheme.primary, TODO: Correct the color
                modifier = GlanceModifier.padding(end = 3.dp)
                    .height(16.dp),
            )
        }

        stopTime.stop?.cardinalPoint?.let {
            Text(
                text = stringResource(it.shortName),
                //color = MaterialTheme.colorScheme.primary, TODO: Correct the color
                modifier = GlanceModifier.padding(end = 3.dp),
            )
        }

        Text(
            text = stopTime.stop?.name ?: stringResource(R.string.error),
            maxLines = 1,
            style = TextStyle(
                fontWeight = if (highlight) FontWeight.Bold else null
            ),
            // overflow = TextOverflow.Ellipsis, TODO: overflow text to ellipsis
            modifier = GlanceModifier
                .run {
                    if (stopTime.arrivalTime == null && stopTime.departureTime == null) {
                        this // do not apply end padding if there is nothing after
                    } else {
                        padding(end = 6.dp)
                    }
                },
            /*color = if (stopTime.stop == null) { TODO: Correct the color
                MaterialTheme.colorScheme.error
            } else if (highlight) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Unspecified
            }*/
        )

        val isLate = trip.lastEventReceivedAt != null
                && !completed
                && trip.delay > 0
        //val lateDecoration = if (isLate) TextDecoration.LineThrough else null
        val highlightWeight = if (highlight) FontWeight.Bold else null

        if (stopTime.arrivalTime != null) {
            // TODO `key` forces recompositions when `lateDecoration` changes, needed because of
            //  probably a bug in Compose (also see below)
            Text(
                text = formatTime(stopTime.arrivalTime),
                maxLines = 1,
                //textDecoration = lateDecoration,
                style = TextStyle(
                    fontWeight = highlightWeight,
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
                        fontWeight = highlightWeight,
                    )
                    //textDecoration = lateDecoration,
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
                        // color = MaterialTheme.colorScheme.error, TODO: Correct the color
                        maxLines = 1,
                        style = TextStyle(
                            fontWeight = highlightWeight,
                        ),
                    )
                }
        }
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 203, heightDp = 130)
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
    TripViewStops(
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
        stopTypeToHighlight = null
    )
}

@OptIn(ExperimentalGlancePreviewApi::class)
// 3x2 Widget
@Preview(widthDp = 203, heightDp = 130)
// 3x3 Widget
@Preview(widthDp = 203, heightDp = 203)
// 3x4 Widget
@Preview(widthDp = 203, heightDp = 276)
@Composable
fun MyWidgetPreview() {
    // Provide mock data to your content
    MyContent()
}