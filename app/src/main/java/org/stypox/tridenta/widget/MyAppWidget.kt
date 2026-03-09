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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.HistoryDao
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
import kotlin.properties.Delegates

class MyAppWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    private lateinit var tripsRepository: LineTripsRepository
    private lateinit var linesRepository: LinesRepository
    private lateinit var historyDao: HistoryDao

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
    val uiState = mutableUiState.asStateFlow()
    private var lineId by Delegates.notNull<Int>()
    private lateinit var lineType: StopLineType

    private var tripReloadJob: Job? = null

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId
    ) {
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        tripsRepository = hiltEntryPoint.lineTripsRepository()
        linesRepository = hiltEntryPoint.lineRepository()
        historyDao = hiltEntryPoint.historyDao()
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
            //var lineType by remember { mutableStateOf(StopLineType.Urban) }

            lineId = prefs[WidgetKeys.LINE_ID] ?: -1
            val lineTypeString = prefs[WidgetKeys.LINE_TYPE]
            if (lineTypeString != null) lineType = StopLineType.valueOf(lineTypeString)
            val tripIndex = prefs[WidgetKeys.TRIP_INDEX]
            val prevTripIndex = prefs[WidgetKeys.PREV_TRIP_INDEX]
            val refreshTimestamp = prefs[WidgetKeys.REFRESH_TIMESTAMP] ?: 0L

            val storedDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            /*if (storedDirectionFilter != null) mutableUiState.update {
                it.copy(
                    directionFilter =
                        Direction.valueOf(storedDirectionFilter)
                )
            }*/
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
            /*if(lineId != -1 && lineTypeString != null) isFavorite = historyDao.isFavorite(
                isLine = true,
                id = lineId,
                type = lineType
            ).value ?: false*/

            /*LaunchedEffect(lineId, lineTypeString) { // retrieving line
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
            }*/

            LaunchedEffect(
                lineId,
                tripIndex,
                prevTripIndex
            ) { // retrieving trip
                if (lineId == -1 || lineTypeString == null) {
                    return@LaunchedEffect
                }
                isFavorite = historyDao.isFavorite(
                    isLine = true,
                    id = lineId,
                    type = lineType
                ).value ?: false
                if (lineTripsUiState.line == null || lineTripsUiState.line!!.lineId != lineId) {
                    loadLine(lineId, lineType)
                }
                if (tripIndex == null) {
                    mutableUiState.update {
                        it.copy(loading = true, error = false)
                    }
                    setReferenceDateTimeAsync(lineTripsUiState.referenceDateTime, context, id)
                    mutableUiState.update {
                        it.copy(loading = false)
                    }
                    return@LaunchedEffect
                }
                loadIndex(tripIndex, context, id)
                /*try {
                    if (lineTripsUiState.directionFilter == Direction.ForwardAndBackward) {
                        val (fetchedTrip, network) = withContext(Dispatchers.IO) {
                            tripsRepository.getUiTrip(
                                lineId,
                                lineType,
                                lineTripsUiState.referenceDateTime,
                                tripIndex
                            )
                        }
                        mutableUiState.update { it.copy(trip = fetchedTrip, tripIndex = tripIndex) }
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
                        mutableUiState.update {
                            it.copy(
                                trip = data.first,
                                tripIndex = data.second
                            )
                        }
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
                }*/
            }

            LaunchedEffect(storedDirectionFilter) {
                if (storedDirectionFilter == null) return@LaunchedEffect
                val newDirectionFilter = Direction.valueOf(storedDirectionFilter)
                mutableUiState.update { it.copy(directionFilter = newDirectionFilter) }

                if (newDirectionFilter == Direction.ForwardAndBackward) {
                    val state = uiState.value
                    if (state.trip == null) {
                        // the trip can be null if there is no trip in that direction
                        loadIndex(state.tripIndex, context, id)
                    } else {
                        // no need to load the trip, as it's already loaded
                        mutableUiState.update {
                            it.copy(
                                prevEnabled = state.tripIndex > 0,
                                nextEnabled = state.tripIndex < uiState.value.tripsInDayCount - 1,
                                directionFilter = newDirectionFilter,
                            )
                        }
                    }

                } else {
                    val state = uiState.value
                    if (state.trip?.direction != newDirectionFilter) {
                        // we need to load another trip, since the current one has the wrong direction
                        loadIndex(state.tripIndex, context, id)
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[WidgetKeys.TRIP_INDEX] = lineTripsUiState.tripIndex
                            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = lineTripsUiState.tripsInDayCount
                            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
                            prefs[WidgetKeys.PREV_ENABLED] = lineTripsUiState.tripIndex > 0
                            prefs[WidgetKeys.NEXT_ENABLED] =
                                lineTripsUiState.tripIndex < (prefs[WidgetKeys.TRIPS_IN_DAY_COUNT]
                                    ?: 0) - 1
                        }
                    }
                }
            }

            LaunchedEffect(refreshTimestamp) { // updating trip
                if (refreshTimestamp == 0L || tripIndex == null || lineTripsUiState.trip == null) return@LaunchedEffect
                //updateTrip(tripIndex, mutableUiState, lineTripsUiState.trip!!)
                cancelTripReloadJobAndLaunch { onReloadAsync(context, id) }
            }

            logInfo("${System.currentTimeMillis()}")
            logInfo("tripIndex: $tripIndex")
            logInfo("prevTripIndex: $prevTripIndex")
            logInfo("directionFilter: ${lineTripsUiState.directionFilter}")
            logInfo("isFavorite: $isFavorite")

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

    private fun loadLine(lineId: Int, lineType: StopLineType) {
        // this job is independent from trip reloading jobs, so don't use cancelReloadJobAndLaunch
        CoroutineScope(Dispatchers.IO).launch {
            val line = withContext(Dispatchers.IO) {
                try {
                    linesRepository.getUiLine(lineId, lineType)
                        .also {
                            if (it == null) {
                                logError(
                                    "UI line (${lineId}, ${lineType}) not found"
                                )
                            }

                            // register a view for this line (assuming loadLine is called once)
                            historyDao.registerAccessed(true, lineId, lineType)
                        }
                } catch (e: Throwable) {
                    logError("Could not load UI line (${lineId}, ${lineType})", e)
                    null
                }
            }
            mutableUiState.update { it.copy(line = line) }
        }
    }

    private fun cancelTripReloadJobAndLaunch(tripLoadingFunction: suspend () -> Unit) {
        tripReloadJob?.cancel()
        tripReloadJob = CoroutineScope(Dispatchers.IO).launch {
            // clear errors and set the state to "loading" before starting to load
            mutableUiState.update { it.copy(loading = true, error = false) }
            tripLoadingFunction()
            // set "loading" to false when loading finishes (errors are set inside the function)
            mutableUiState.update { it.copy(loading = false) }
        }
    }

    private fun loadIndex(index: Int, context: Context, id: GlanceId) {
        logInfo("loadIndex: $index")
        if (index >= 0 && index < uiState.value.tripsInDayCount) {
            // only cancel any currently running job if there is something to do; the above
            // condition will be false e.g. when there are no trips in a day (but not only for that)
            cancelTripReloadJobAndLaunch {
                loadIndexAsync(index, context, id)
            }
        }
    }

    private suspend fun loadIndexAsync(index: Int, context: Context, id: GlanceId) {
        // hide the current trip, as it's going to change
        val prevState = mutableUiState.getAndUpdate {
            it.copy(
                tripIndex = index,
                trip = null,
                prevEnabled = index > 0,
                nextEnabled = index < uiState.value.tripsInDayCount - 1,
            )
        }

        // load the trip, and show it right after it gets loaded (see the related functions)
        val (trip, network) = if (prevState.directionFilter == Direction.ForwardAndBackward) {
            loadIndexNoFilterAsync(index,context,id)
        } else {
            loadIndexDirectionAsync(index, prevState, context, id)
        }

        if (!network && trip != null && trip.completedStops < trip.stopTimes.size) {
            // after showing the (possibly) outdated trip fast, reload it to show latest updates
            // (but reload it only if there actually is a trip and it is not completed)
            onReloadAsync(context, id)
        }
    }

    private suspend fun setReferenceDateTimeAsync(
        referenceDateTimeCurrentZone: ZonedDateTime,
        context: Context,
        id: GlanceId
    ) {
        val referenceDateTime = referenceDateTimeCurrentZone.withZoneSameInstant(ROME_ZONE_ID)
        mutableUiState.update {
            it.copy(
                tripsInDayCount = 0,
                tripIndex = 0,
                trip = null,
                prevEnabled = false,
                nextEnabled = false,
                referenceDateTime = referenceDateTime,
            )
        }

        var error = false
        val (tripsInDayCount, tripIndex, trip) = withContext(Dispatchers.IO) {
            try {
                tripsRepository.getUiTrip(
                    lineId = lineId,
                    lineType = lineType,
                    referenceDateTime = referenceDateTime,
                    directionFilter = uiState.value.directionFilter,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip for UI line (${mutableUiState.value.line?.lineId}, " +
                            "${mutableUiState.value.line?.type}) at time $referenceDateTime",
                    e
                )
                error = true
                Triple(0, 0, null)
            }
        }

        mutableUiState.update {
            it.copy(
                tripsInDayCount = tripsInDayCount,
                tripIndex = tripIndex,
                trip = trip,
                prevEnabled = tripIndex > 0,
                nextEnabled = tripIndex < tripsInDayCount - 1,
                referenceDateTime = referenceDateTime,
                error = error,
            )
        }
        updateAppWidgetState(context, id) { prefs ->
            prefs[WidgetKeys.TRIP_INDEX] = tripIndex
            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = tripsInDayCount
            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
            prefs[WidgetKeys.PREV_ENABLED] = tripIndex > 0
            prefs[WidgetKeys.NEXT_ENABLED] = tripIndex < tripsInDayCount - 1
        }
    }

    private suspend fun onReloadAsync(context: Context, id: GlanceId) {
        val previousTrip = uiState.value.trip
        if (previousTrip == null) {
            // this could happen if an error happened while loading initial/more trips
            if (uiState.value.tripsInDayCount > 0) {
                // more trips failed loading, try to load again the currently set index
                loadIndexAsync(uiState.value.tripIndex, context, id)
            } else {
                // initial trips failed loading, try to load again the current day
                setReferenceDateTimeAsync(uiState.value.referenceDateTime, context, id)
            }
            return
        }

        val trip = withContext(Dispatchers.IO) {
            try {
                tripsRepository.reloadUiTrip(
                    uiTrip = previousTrip,
                    index = uiState.value.tripIndex,
                    referenceDateTime = uiState.value.referenceDateTime
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip ${previousTrip.tripId} for UI line " +
                            "(${mutableUiState.value.line?.lineId}, ${mutableUiState.value.line?.type})",
                    e
                )
                null
            }
        }

        if (trip == null) {
            // keep previous trip intact, we don't want to hide information that we do have!
            mutableUiState.update { it.copy(error = true) }
        } else {
            mutableUiState.update { it.copy(trip = trip) }
        }
    }

    private suspend fun loadIndexNoFilterAsync(index: Int,context: Context,id: GlanceId): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                tripsRepository.getUiTrip(
                    lineId = mutableUiState.value.line?.lineId!!,
                    lineType = mutableUiState.value.line?.type!!,
                    referenceDateTime = uiState.value.referenceDateTime,
                    index = index,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " +
                            "(${mutableUiState.value.line?.lineId}, ${mutableUiState.value.line?.type})",
                    e
                )
                Pair(null, true /* <- useless when trip == null */)
            }
        }

        // show the trip even if loadedFromNetwork is false (in which case it could be outdated)
        mutableUiState.update {
            it.copy(
                trip = res.first,
                error = res.first == null,
            )
        }
        if (res.first != null) {
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.TRIP_INDEX] = index
                prefs[WidgetKeys.PREV_ENABLED] = index > 0
                prefs[WidgetKeys.NEXT_ENABLED] =
                    index < uiState.value.tripsInDayCount - 1
            }
        }

        return res
    }


    private suspend fun loadIndexDirectionAsync(
        index: Int,
        prevState: LineTripsUiState,
        context: Context,
        id: GlanceId
    ): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                tripsRepository.getUiTripWithDirection(
                    lineId = mutableUiState.value.line?.lineId!!,
                    lineType = mutableUiState.value.line?.type!!,
                    referenceDateTime = uiState.value.referenceDateTime,
                    index = index,
                    direction = prevState.directionFilter,
                    prevIndex = prevState.tripIndex,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " +
                            "(${mutableUiState.value.line?.lineId}, ${mutableUiState.value.line?.type})" +
                            "in direction ${prevState.directionFilter}",
                    e
                )
                null
            }
        }

        if (res == null) {
            // no trip could be loaded in the set direction, restore previous state,
            // but update prevEnabled or nextEnabled
            mutableUiState.update {
                it.copy(
                    tripIndex = prevState.tripIndex,
                    trip = prevState.trip,
                    prevEnabled = if (index < prevState.tripIndex) false else prevState.prevEnabled,
                    nextEnabled = if (index > prevState.tripIndex) false else prevState.nextEnabled,
                )
            }
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.TRIP_INDEX] = prevState.tripIndex
                if (index < prevState.tripIndex) {
                    prefs[WidgetKeys.PREV_ENABLED] = false
                }
                if (index > prevState.tripIndex) {
                    prefs[WidgetKeys.NEXT_ENABLED] = false
                }
            }

            return Pair(null, true /* <- useless when trip == null */)

        } else {
            val (trip, newIndex, loadedFromNetwork) = res
            mutableUiState.update {
                it.copy(
                    tripIndex = newIndex,
                    trip = trip,
                    prevEnabled = newIndex > 0,
                    nextEnabled = newIndex < uiState.value.tripsInDayCount - 1,
                )
            }
            updateAppWidgetState(context, id) { prefs ->
                prefs[WidgetKeys.TRIP_INDEX] = newIndex
                prefs[WidgetKeys.PREV_ENABLED] = newIndex > 0
                prefs[WidgetKeys.NEXT_ENABLED] =
                    newIndex < uiState.value.tripsInDayCount - 1
            }

            return Pair(trip, loadedFromNetwork)
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