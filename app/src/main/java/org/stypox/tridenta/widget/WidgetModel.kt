package org.stypox.tridenta.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
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
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.ui.line_trips.LineTripsUiState
import org.stypox.tridenta.widget.actions.WidgetEntryPoint
import org.stypox.tridenta.widget.actions.WidgetKeys
import java.time.ZonedDateTime
import kotlin.properties.Delegates

class WidgetModel {
    private val context: Context
    private val glanceId: GlanceId
    val tripsRepository: LineTripsRepository
    val linesRepository: LinesRepository
    val historyDao: HistoryDao

    val mutableUiState = MutableStateFlow(
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
    var lineId by Delegates.notNull<Int>()
    private lateinit var lineType: StopLineType

    private var tripReloadJob: Job? = null

    constructor(aContext: Context, aGlanceId: GlanceId) {
        context = aContext
        glanceId = aGlanceId
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        tripsRepository = hiltEntryPoint.lineTripsRepository()
        linesRepository = hiltEntryPoint.lineRepository()
        historyDao = hiltEntryPoint.historyDao()
    }

    suspend fun initState() {
        val prefs = getAppWidgetState(
            context,
            PreferencesGlanceStateDefinition, glanceId
        )
        lineId = prefs[WidgetKeys.LINE_ID] ?: -1
        val lineTypeString = prefs[WidgetKeys.LINE_TYPE] ?: StopLineType.Urban.name
        lineType = StopLineType.valueOf(lineTypeString)
        mutableUiState.update {
            it.copy(
                tripIndex = prefs[WidgetKeys.TRIP_INDEX] ?: 0,
                tripsInDayCount = prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] ?: 0,
                prevEnabled = prefs[WidgetKeys.PREV_ENABLED] ?: false,
                nextEnabled = prefs[WidgetKeys.NEXT_ENABLED] ?: false,
            )
        }
    }


    fun loadLine() {
        // this job is independent from trip reloading jobs, so don't use cancelReloadJobAndLaunch
        CoroutineScope(Dispatchers.IO).launch {
            val line = withContext(Dispatchers.IO) {
                try {
                    linesRepository.getUiLine(lineId, lineType).also {
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

    fun cancelTripReloadJobAndLaunch(tripLoadingFunction: suspend () -> Unit) {
        tripReloadJob?.cancel()
        tripReloadJob = CoroutineScope(Dispatchers.IO).launch {
            // clear errors and set the state to "loading" before starting to load
            mutableUiState.update { it.copy(loading = true, error = false) }
            tripLoadingFunction()
            // set "loading" to false when loading finishes (errors are set inside the function)
            mutableUiState.update { it.copy(loading = false) }
        }
    }

    fun loadIndex(index: Int) {
        logInfo("loadIndex: $index")
        if (index >= 0 && index < uiState.value.tripsInDayCount) {
            // only cancel any currently running job if there is something to do; the above
            // condition will be false e.g. when there are no trips in a day (but not only for that)
            cancelTripReloadJobAndLaunch {
                loadIndexAsync(index)
            }
        }
    }

    private suspend fun loadIndexAsync(index: Int) {
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
            loadIndexNoFilterAsync(index)
        } else {
            loadIndexDirectionAsync(index, prevState)
        }

        if (!network && trip != null && trip.completedStops < trip.stopTimes.size) {
            // after showing the (possibly) outdated trip fast, reload it to show latest updates
            // (but reload it only if there actually is a trip and it is not completed)
            onReloadAsync()
        }
    }

    suspend fun setReferenceDateTimeAsync(
        referenceDateTimeCurrentZone: ZonedDateTime,
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
                    "Could not load trip for UI line (${mutableUiState.value.line?.lineId}, " + "${mutableUiState.value.line?.type}) at time $referenceDateTime",
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
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.TRIP_INDEX] = tripIndex
            prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] = tripsInDayCount
            prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
            prefs[WidgetKeys.PREV_ENABLED] = tripIndex > 0
            prefs[WidgetKeys.NEXT_ENABLED] = tripIndex < tripsInDayCount - 1
        }
        logInfo("setReferenceDateTimeAsync tripIndex: $tripIndex")
    }

    suspend fun onReloadAsync() {
        val previousTrip = uiState.value.trip
        if (previousTrip == null) {
            // this could happen if an error happened while loading initial/more trips
            if (uiState.value.tripsInDayCount > 0) {
                // more trips failed loading, try to load again the currently set index
                loadIndexAsync(uiState.value.tripIndex)
            } else {
                // initial trips failed loading, try to load again the current day
                setReferenceDateTimeAsync(uiState.value.referenceDateTime)
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
                    "Could not load trip ${previousTrip.tripId} for UI line " + "(${mutableUiState.value.line?.lineId}, ${mutableUiState.value.line?.type})",
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

    private suspend fun loadIndexNoFilterAsync(
        index: Int
    ): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                tripsRepository.getUiTrip(
                    lineId = lineId,
                    lineType = lineType,
                    referenceDateTime = uiState.value.referenceDateTime,
                    index = index,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " + "(${lineId}, ${lineType})",
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
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetKeys.TRIP_INDEX] = index
                prefs[WidgetKeys.PREV_ENABLED] = index > 0
                prefs[WidgetKeys.NEXT_ENABLED] = index < uiState.value.tripsInDayCount - 1
            }
        }

        return res
    }


    private suspend fun loadIndexDirectionAsync(
        index: Int, prevState: LineTripsUiState
    ): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                tripsRepository.getUiTripWithDirection(
                    lineId = lineId,
                    lineType = lineType,
                    referenceDateTime = uiState.value.referenceDateTime,
                    index = index,
                    direction = prevState.directionFilter,
                    prevIndex = prevState.tripIndex,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " + "(${mutableUiState.value.line?.lineId}, ${mutableUiState.value.line?.type})" + "in direction ${prevState.directionFilter}",
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
            updateAppWidgetState(context, glanceId) { prefs ->
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
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetKeys.TRIP_INDEX] = newIndex
                prefs[WidgetKeys.PREV_TRIP_INDEX] = prevState.tripIndex
                prefs[WidgetKeys.PREV_ENABLED] = newIndex > 0
                prefs[WidgetKeys.NEXT_ENABLED] = newIndex < uiState.value.tripsInDayCount - 1
            }

            return Pair(trip, loadedFromNetwork)
        }
    }
}