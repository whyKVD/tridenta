package org.stypox.tridenta.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.stypox.tridenta.repo.data.UiStopTime
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.ui.MainActivity
import org.stypox.tridenta.widget.actions.NextTripAction
import org.stypox.tridenta.widget.actions.PrevTripAction
import org.stypox.tridenta.widget.actions.ReloadTripAction
import org.stypox.tridenta.widget.actions.WidgetEntryPoint
import org.stypox.tridenta.widget.actions.WidgetKeys
import org.stypox.tridenta.widget.ui.TripViewGlance
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MyAppWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        // In this method, load data needed to render the AppWidget.
        // Use `withContext` to switch to another thread for long running
        // operations.

        provideContent {
            val prefs = currentState<Preferences>()
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
            val configIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

                // These flags ensure the activity opens properly from the launcher context
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            var trip by remember { mutableStateOf<UiTrip?>(null) }
            var isError = false
            var isLoading by remember { mutableStateOf(true) }
            var directionFilter by remember {
                mutableStateOf(
                    Direction.ForwardAndBackward.name
                )
            }
            val storedDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            if (storedDirectionFilter != null) directionFilter = storedDirectionFilter
            val lineId = prefs[WidgetKeys.LINE_ID]
            val lineTypeString = prefs[WidgetKeys.LINE_TYPE]
            val tripIndex = prefs[WidgetKeys.TRIP_INDEX]
            val prevTripIndex = prefs[WidgetKeys.PREV_TRIP_INDEX]
            val refreshTimestamp = prefs[WidgetKeys.REFRESH_TIMESTAMP] ?: 0L
            val hiltEntryPoint =
                EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
            val tripsRepository = hiltEntryPoint.lineTripsRepository()
            LaunchedEffect(lineId, lineTypeString, directionFilter, tripIndex) {
                if (lineId == null || lineTypeString == null) {
                    return@LaunchedEffect
                }
                try {
                    if (tripIndex == null) {
                        val fetchedTrip = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTrip(
                                lineId,
                                StopLineType.valueOf(lineTypeString),
                                ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID),
                                Direction.valueOf(directionFilter)
                            )
                        }
                        trip = fetchedTrip.third
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = fetchedTrip.second
                            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = fetchedTrip.first
                            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
                        }
                        return@LaunchedEffect
                    }
                    if (Direction.valueOf(directionFilter) == Direction.ForwardAndBackward) {
                        val (fetchedTrip, network) = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTrip(
                                lineId,
                                StopLineType.valueOf(lineTypeString),
                                ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID),
                                tripIndex
                            )
                        }
                        trip = fetchedTrip
                        if (!network) {
                            isLoading = true
                            try {
                                val freshTrip = withContext(Dispatchers.IO) {
                                    tripsRepository.reloadUiTrip(
                                        trip!!,
                                        tripIndex,
                                        ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
                                    )
                                }

                                trip = freshTrip
                            } catch (e: Exception) {
                                logError(e.message!!, e.cause)
                            } finally {
                                isLoading = false
                            }
                        }
                    } else {
                        if (prevTripIndex == null) {
                            throw Error("PrevTripIndex cannot be null")
                        }
                        val data = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTripWithDirection(
                                lineId,
                                StopLineType.valueOf(lineTypeString),
                                ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID),
                                Direction.valueOf(directionFilter),
                                tripIndex,
                                prevTripIndex
                            )
                        }
                        if (data == null) {
                            throw Error("data cannot be null")
                        }
                        trip = data.first
                        if (!data.third) {
                            isLoading = true
                            try {
                                val freshTrip = withContext(Dispatchers.IO) {
                                    tripsRepository.reloadUiTrip(
                                        trip!!,
                                        tripIndex,
                                        ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
                                    )
                                }

                                trip = freshTrip
                            } catch (e: Exception) {
                                logError(e.message!!, e.cause)
                            } finally {
                                isLoading = false
                            }
                        }
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = data.second
                        }
                    }
                } catch (e: Exception) {
                    logError(e.message!!, e.cause)
                    isError = true
                } finally {
                    isLoading = false
                }
            }
            LaunchedEffect(refreshTimestamp) {
                if (refreshTimestamp == 0L || trip == null || tripIndex == null) return@LaunchedEffect
                isLoading = true
                logInfo("Performed refresh")
                try {
                    val freshTrip = withContext(Dispatchers.IO) {
                        tripsRepository.reloadUiTrip(
                            trip!!,
                            tripIndex,
                            ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
                        )
                    }

                    trip = freshTrip
                } catch (e: Exception) {
                    logError(e.message!!, e.cause)
                } finally {
                    isLoading = false
                }
            }
            logInfo("tripIndex: $tripIndex")
            logInfo("prevTripIndex: $prevTripIndex")
            GlanceTheme {
                TripViewGlance(
                    trip, error = isError, loading = isLoading, /*&& isInitalDataLoaded*/
                    onReloadAction = actionRunCallback<ReloadTripAction>(),
                    onPrevAction = actionRunCallback<PrevTripAction>(),
                    onNextAction = actionRunCallback<NextTripAction>(),
                    onLineClickAction = actionStartActivity(configIntent),
                    //onDirectionClickAction = actionRunCallback<ToggleDirectionAction>(),
                    stopIdToHighlight = null,
                    stopTypeToHighlight = null,
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
            onLineClickAction = actionStartActivity<MainActivity>(),
            //onDirectionClickAction = actionStartActivity<MainActivity>(),
            stopIdToHighlight = null,
            stopTypeToHighlight = null,
        )
    }
}