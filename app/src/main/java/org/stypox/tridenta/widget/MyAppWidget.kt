package org.stypox.tridenta.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.CardinalPoint
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.ui.MainActivity
import org.stypox.tridenta.ui.line_trips.LineTripsUiState
import org.stypox.tridenta.widget.actions.NextTripAction
import org.stypox.tridenta.widget.actions.PrevTripAction
import org.stypox.tridenta.widget.actions.ReloadTripAction
import org.stypox.tridenta.widget.actions.ToggleDirectionAction
import org.stypox.tridenta.widget.actions.WidgetEntryPoint
import org.stypox.tridenta.widget.actions.WidgetKeys
import org.stypox.tridenta.widget.ui.LineTripsWidgetScreen
import org.stypox.tridenta.widget.ui.TripViewGlance
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MyAppWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    private lateinit var tripsRepository: LineTripsRepository
    private lateinit var linesRepository: LinesRepository

    //private lateinit var referenceDateTime: ZonedDateTime
    private val mutableUiState = MutableStateFlow(
        LineTripsUiState(
            line = null,
            tripsInDayCount = 0,
            tripIndex = 0,
            trip = null,
            prevEnabled = false,
            nextEnabled = false,
            referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID),
            directionFilter = Direction.ForwardAndBackward,
            stopIdToHighlight = null,
            stopTypeToHighlight = null,
            loading = true,
            error = false,
        )
    )
    private val uiState = mutableUiState.asStateFlow()

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        tripsRepository = hiltEntryPoint.lineTripsRepository()
        linesRepository = hiltEntryPoint.lineRepository()
        //referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
        mutableUiState.update {
            it.copy(
                referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
            )
        }

        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val configIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        provideContent {
            val lineTripsUiState by uiState.collectAsState()
            val prefs = currentState<Preferences>()
            var isFavorite by remember { mutableStateOf(false) }
            var lineType by remember { mutableStateOf(StopLineType.Urban) }

            val lineId = prefs[WidgetKeys.LINE_ID]
            val lineTypeString = prefs[WidgetKeys.LINE_TYPE]
            if (lineTypeString != null) lineType = StopLineType.valueOf(lineTypeString)
            val tripIndex = prefs[WidgetKeys.TRIP_INDEX]
            var prevTripIndex = prefs[WidgetKeys.PREV_TRIP_INDEX]
            val refreshTimestamp = prefs[WidgetKeys.REFRESH_TIMESTAMP] ?: 0L

            val storedDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            if (storedDirectionFilter != null) mutableUiState.update {
                it.copy(
                    directionFilter =
                        Direction.valueOf(storedDirectionFilter)
                )
            }
            val prevEnabledStored = prefs[WidgetKeys.PREV_ENABLED]
            if (prevEnabledStored != null) mutableUiState.update {
                it.copy(prevEnabled = prevEnabledStored)
            }
            val nextEnabledStored = prefs[WidgetKeys.NEXT_ENABLED]
            if (nextEnabledStored != null) mutableUiState.update {
                it.copy(
                    nextEnabled = nextEnabledStored
                )
            }

            LaunchedEffect(lineId, lineTypeString) { // retrieving line
                if (lineId == null || lineTypeString == null) {
                    return@LaunchedEffect
                }
                try {
                    val fetchedLine = withContext(Dispatchers.IO) {
                        linesRepository.getUiLine(lineId, lineType)
                    }

                    mutableUiState.update {
                        it.copy(line = fetchedLine)
                    }
                    fetchedLine?.let { isFavorite = it.isFavorite }
                } catch (e: Exception) {
                    mutableUiState.update { it.copy(error = true) }
                    logError(e.message!!, e.cause)
                }
            }

            LaunchedEffect(
                lineId,
                storedDirectionFilter,
                tripIndex,
                prevTripIndex
            ) { // retrieving trip
                if (lineId == null || lineTypeString == null) {
                    return@LaunchedEffect
                }
                try {
                    if (tripIndex == null) {
                        val data = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTrip(
                                lineId,
                                lineType,
                                lineTripsUiState.referenceDateTime,
                                lineTripsUiState.directionFilter
                            )
                        }
                        mutableUiState.update { it.copy(trip = data.third) }
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = data.second
                            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = data.first
                            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
                            prefs[WidgetKeys.PREV_ENABLED] = data.second > 0
                            prefs[WidgetKeys.NEXT_ENABLED] =
                                data.second < (prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] ?: 0) - 1
                            mutableUiState.update {
                                it.copy(
                                    prevEnabled = prefs[WidgetKeys.PREV_ENABLED] ?: false,
                                    nextEnabled = prefs[WidgetKeys.NEXT_ENABLED] ?: false
                                )
                            }
                        }
                        return@LaunchedEffect
                    }
                    if (lineTripsUiState.directionFilter == Direction.ForwardAndBackward) {
                        val (fetchedTrip, network) = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTrip(
                                lineId,
                                lineType,
                                lineTripsUiState.referenceDateTime,
                                tripIndex
                            )
                        }
                        mutableUiState.update { it.copy(trip = fetchedTrip) }
                        if (!network) {
                            updateTrip(tripIndex, mutableUiState, fetchedTrip)
                        }
                    } else {
                        if (prevTripIndex == null) {
                            prevTripIndex = tripIndex
                        }
                        val data = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTripWithDirection(
                                lineId,
                                lineType,
                                lineTripsUiState.referenceDateTime,
                                lineTripsUiState.directionFilter,
                                tripIndex,
                                prevTripIndex
                            )
                        }
                        if (data == null) {
                            updateAppWidgetState(context, id) { prefs ->
                                prefs[WidgetKeys.TRIP_INDEX] = prevTripIndex
                                if (tripIndex < prevTripIndex) {
                                    prefs[WidgetKeys.PREV_ENABLED] = false
                                    mutableUiState.update { it.copy(prevEnabled = false) }
                                }
                                if (tripIndex > prevTripIndex) {
                                    prefs[WidgetKeys.NEXT_ENABLED] = false
                                    mutableUiState.update { it.copy(nextEnabled = false) }
                                }
                            }
                            return@LaunchedEffect
                        }
                        mutableUiState.update { it.copy(trip = data.first) }
                        if (!data.third) {
                            updateTrip(tripIndex, mutableUiState, data.first)
                        }
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = data.second
                            prefs[WidgetKeys.PREV_TRIP_INDEX] = tripIndex
                        }
                    }
                } catch (e: Exception) {
                    logError(e.message!!, e.cause)
                    mutableUiState.update { it.copy(error = true) }
                } finally {
                    mutableUiState.update { it.copy(loading = false) }
                }
            }

            LaunchedEffect(refreshTimestamp) { // updating trip
                if (refreshTimestamp == 0L || tripIndex == null || lineTripsUiState.trip == null) return@LaunchedEffect
                updateTrip(tripIndex, mutableUiState, lineTripsUiState.trip!!)
            }

            logInfo("${System.currentTimeMillis()}")
            logInfo("tripIndex: $tripIndex")
            logInfo("prevTripIndex: $prevTripIndex")
            logInfo("directionFilter: ${lineTripsUiState.directionFilter}")

            GlanceTheme {
                LineTripsWidgetScreen(
                    line = lineTripsUiState.line,
                    trip = lineTripsUiState.trip,
                    error = lineTripsUiState.error,
                    loading = lineTripsUiState.loading,
                    onReloadAction = actionRunCallback<ReloadTripAction>(),
                    onPrevAction = actionRunCallback<PrevTripAction>(),
                    onNextAction = actionRunCallback<NextTripAction>(),
                    onLineClickAction = actionStartActivity(configIntent),
                    directionFilter = lineTripsUiState.directionFilter,
                    onDirectionClickAction = actionRunCallback<ToggleDirectionAction>(),
                    stopIdToHighlight = null,
                    stopTypeToHighlight = null,
                    prevEnabled = lineTripsUiState.prevEnabled,
                    nextEnabled = lineTripsUiState.nextEnabled,
                    isFavorite = isFavorite
                )
            }
        }
    }

    private suspend fun updateTrip(
        tripIndex: Int,
        mutableUiState: MutableStateFlow<LineTripsUiState>,
        trip: UiTrip
    ) {
        mutableUiState.update { it.copy(loading = true) }
        try {
            val freshTrip = withContext(Dispatchers.IO) {
                tripsRepository.reloadUiTrip(
                    trip,
                    tripIndex,
                    mutableUiState.value.referenceDateTime
                )
            }

            mutableUiState.update { it.copy(trip = freshTrip) }
        } catch (e: Exception) {
            mutableUiState.update { it.copy(error = true) }
            logError(e.message!!, e.cause)
        } finally {
            mutableUiState.update { it.copy(loading = false) }
        }
    }
}

@OptIn(ExperimentalGlancePreviewApi::class)
// 3x3 Widget
@Preview(widthDp = 250, heightDp = 203)
// 3x4 Widget
@Preview(widthDp = 250, heightDp = 276)
@Composable
fun MyWidgetPreview() {
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
    // Provide mock data to your content
    GlanceTheme {
        TripViewGlance(
            UiTrip(
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
            error = false, loading = false,
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