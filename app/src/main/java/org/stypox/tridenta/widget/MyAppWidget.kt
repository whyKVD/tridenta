package org.stypox.tridenta.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.update
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.CardinalPoint
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.ui.MainActivity
import org.stypox.tridenta.widget.actions.NextTripAction
import org.stypox.tridenta.widget.actions.PrevTripAction
import org.stypox.tridenta.widget.actions.ReloadTripAction
import org.stypox.tridenta.widget.actions.ToggleDirectionAction
import org.stypox.tridenta.widget.actions.WidgetKeys
import org.stypox.tridenta.widget.ui.LineTripsWidgetScreen
import org.stypox.tridenta.widget.ui.TripViewGlance
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MyAppWidget : GlanceAppWidget() {
    lateinit var model: WidgetModel
    override suspend fun provideGlance(
        context: Context, id: GlanceId
    ) {
        model = WidgetModel(context, id)
        model.initState()

        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val configIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
        provideContent {
            val lineTripsUiState by model.uiState.collectAsState()
            val prefs = currentState<Preferences>()

            val lineId = prefs[WidgetKeys.LINE_ID] ?: -1
            val lineTypeString = prefs[WidgetKeys.LINE_TYPE]
            val tripIndex = prefs[WidgetKeys.TRIP_INDEX]
            val toggledDirection = prefs[WidgetKeys.TOGGLED_DIRECTION] ?: false
            val prevTripIndex = prefs[WidgetKeys.PREV_TRIP_INDEX]
            val refreshTimestamp = prefs[WidgetKeys.REFRESH_TIMESTAMP] ?: 0L
            val storedDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            val tripsInDayCount = prefs[WidgetKeys.TRIPS_IN_DAY_COUNT]

            LaunchedEffect(lineId) { // retrieving line
                if (lineId == -1 || lineTypeString == null) {
                    return@LaunchedEffect
                }
                model.initState()
                if (lineTripsUiState.line == null || lineTripsUiState.line!!.lineId != lineId) {
                    model.loadLine()
                }
            }

            LaunchedEffect(
                model.lineId, tripIndex, prevTripIndex, tripsInDayCount
            ) { // retrieving trip
                if (tripIndex == null) {
                    model.cancelTripReloadJobAndLaunch {
                        model.setReferenceDateTimeAsync(lineTripsUiState.referenceDateTime)
                    }
                    return@LaunchedEffect
                }
                model.loadIndex(tripIndex)
                model.initState()
            }

            LaunchedEffect(storedDirectionFilter) {
                if (storedDirectionFilter == null || !toggledDirection) return@LaunchedEffect
                val newDirectionFilter = Direction.valueOf(storedDirectionFilter)
                model.mutableUiState.update { it.copy(directionFilter = newDirectionFilter) }

                if (newDirectionFilter == Direction.ForwardAndBackward) {
                    val state = model.uiState.value
                    if (state.trip == null) {
                        // the trip can be null if there is no trip in that direction
                        model.loadIndex(state.tripIndex)
                    } else {
                        // no need to load the trip, as it's already loaded
                        model.mutableUiState.update {
                            it.copy(
                                prevEnabled = state.tripIndex > 0,
                                nextEnabled = state.tripIndex < model.uiState.value.tripsInDayCount - 1,
                                directionFilter = newDirectionFilter,
                            )
                        }
                    }

                } else {
                    val state = model.uiState.value
                    if (state.trip?.direction != newDirectionFilter) {
                        // we need to load another trip, since the current one has the wrong direction
                        model.loadIndex(state.tripIndex)
                        logInfo("state.tripIndex: ${state.tripIndex}")
                        logInfo("lineTripsUiState.tripIndex: ${lineTripsUiState.tripIndex}")
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = lineTripsUiState.tripIndex
                            prefs[WidgetKeys.PREV_TRIP_INDEX] = state.tripIndex
                            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = lineTripsUiState.tripsInDayCount
                            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
                            prefs[WidgetKeys.DIRECTION_FILTER] =
                                lineTripsUiState.directionFilter.name
                            prefs[WidgetKeys.PREV_ENABLED] = lineTripsUiState.tripIndex > 0
                            prefs[WidgetKeys.NEXT_ENABLED] =
                                lineTripsUiState.tripIndex < lineTripsUiState.tripsInDayCount - 1
                        }
                        this@MyAppWidget.update(context, id)
                    }
                }
                updateAppWidgetState(context, id) { prefs ->
                    prefs[WidgetKeys.TOGGLED_DIRECTION] = false
                }
            }

            if (storedDirectionFilter != null && Direction.valueOf(storedDirectionFilter) != lineTripsUiState.directionFilter) {
                model.mutableUiState.update {
                    it.copy(
                        directionFilter = Direction.valueOf(storedDirectionFilter)
                    )
                }
            }

            LaunchedEffect(refreshTimestamp) { // updating trip
                if (refreshTimestamp == 0L || tripIndex == null || lineTripsUiState.trip == null) return@LaunchedEffect
                //updateTrip(tripIndex, mutableUiState, lineTripsUiState.trip!!)
                model.cancelTripReloadJobAndLaunch { model.onReloadAsync() }
            }

            logInfo("${System.currentTimeMillis()}")
            logInfo("tripIndex: $tripIndex")
            logInfo("lineTripsUiState.tripIndex: ${lineTripsUiState.tripIndex}")
            logInfo("trip: ${lineTripsUiState.trip}")
            logInfo("prevTripIndex: $prevTripIndex")
            logInfo("directionFilter: ${lineTripsUiState.directionFilter}")
            logInfo("isFavorite: ${lineTripsUiState.line?.isFavorite}")
            logInfo("isLoading: ${lineTripsUiState.loading}")

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
                    isFavorite = lineTripsUiState.line?.isFavorite ?: false
                )
            }
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
        ), DbStop(
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
        ), DbStop(
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
        ), DbStop(
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
    val referenceDateTime = OffsetDateTime.of(2022, 9, 26, 9, 33, 17, 328943849, ZoneOffset.UTC)
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