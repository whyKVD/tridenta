package org.stypox.tridenta.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.HistoryDao
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.enums.StopLineType
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.log.logWarning
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.repo.StopTripsRepository
import org.stypox.tridenta.repo.StopsRepository
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.widget.LineTripWidget
import org.stypox.tridenta.widget.LineTripWidgetStateDefinition
import org.stypox.tridenta.widget.WidgetState
import java.time.ZonedDateTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun lineTripsRepository(): LineTripsRepository
    fun lineRepository(): LinesRepository
    fun historyDao(): HistoryDao

    suspend fun loadIndex(index: Int, glanceId: GlanceId, context: Context) {
        logInfo("loadIndex: $index")
        val state: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        if (state !is WidgetState.LineTripsAvailable) return
        if (index < 0) {
            logWarning("index must be positive")
            return
        }
        if (index > state.tripsInDayCount - 1) {
            logWarning("index must be less than tripsInDayCount")
            return
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
            state.copy(loading = true)
        }
        LineTripWidget().update(context, glanceId)
        // only cancel any currently running job if there is something to do; the above
        // condition will be false e.g. when there are no trips in a day (but not only for that)
        loadIndexAsync(index, state, context, glanceId)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { s ->
            if (s is WidgetState.LineTripsAvailable) s.copy(loading = false) else s
        }
        LineTripWidget().update(context, glanceId)
    }

    private suspend fun loadIndexAsync(
        index: Int,
        state: WidgetState.LineTripsAvailable,
        context: Context,
        glanceId: GlanceId
    ) {
        // hide the current trip, as it's going to change
        val prevState = state
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.LineTripsAvailable) state.copy(
                tripIndex = index,
                trip = null,
                prevEnabled = index > 0,
                nextEnabled = index < state.tripsInDayCount - 1,
            ) else state
        }
        LineTripWidget().update(context, glanceId)

        // load the trip, and show it right after it gets loaded (see the related functions)
        val (trip, network) = if (prevState.directionFilter == Direction.ForwardAndBackward) {
            loadIndexNoFilterAsync(index, prevState, context, glanceId)
        } else {
            loadIndexDirectionAsync(index, prevState, context, glanceId)
        }

        if (!network && trip != null && trip.completedStops < trip.stopTimes.size) {
            // after showing the (possibly) outdated trip fast, reload it to show latest updates
            // (but reload it only if there actually is a trip and it is not completed)
            onReloadAsync(context, glanceId)
        }
    }

    suspend fun setReferenceDateTimeAsync(
        referenceDateTimeCurrentZone: ZonedDateTime,
        state: WidgetState.LineTripsAvailable,
        context: Context,
        glanceId: GlanceId
    ) {
        val referenceDateTime = referenceDateTimeCurrentZone.withZoneSameInstant(ROME_ZONE_ID)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.LineTripsAvailable) state.copy(
                tripsInDayCount = 0,
                tripIndex = 0,
                trip = null,
                prevEnabled = false,
                nextEnabled = false,
                referenceDateTime = referenceDateTime,
            ) else state
        }
        LineTripWidget().update(context, glanceId)

        var error = false
        val (tripsInDayCount, tripIndex, trip) = withContext(Dispatchers.IO) {
            try {
                lineTripsRepository().getUiTrip(
                    lineId = state.line?.lineId!!,
                    lineType = state.line.type,
                    referenceDateTime = referenceDateTime,
                    directionFilter = state.directionFilter,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip for UI line (${state.line?.lineId}, " + "${state.line?.type}) at time $referenceDateTime",
                    e
                )
                error = true
                Triple(0, 0, null)
            }
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.LineTripsAvailable) state.copy(
                tripsInDayCount = tripsInDayCount, tripIndex = tripIndex, trip = trip,
                prevEnabled = tripIndex > 0,
                nextEnabled = tripIndex < tripsInDayCount - 1,
                referenceDateTime = referenceDateTime,
                error = error
            ) else state
        }
        LineTripWidget().update(context, glanceId)
    }

    suspend fun onReloadAsync(context: Context, glanceId: GlanceId) {
        val state: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        if (state !is WidgetState.LineTripsAvailable) return
        val previousTrip = state.trip
        if (previousTrip == null) {// this could happen if an error happened while loading initial/more trips
            if (state.tripsInDayCount > 0) {
                // more trips failed loading, try to load again the currently set index
                loadIndexAsync(state.tripIndex, state, context, glanceId)
            } else {
                // initial trips failed loading, try to load again the current day
                setReferenceDateTimeAsync(state.referenceDateTime, state, context, glanceId)
            }
            return
        }

        val trip = withContext(Dispatchers.IO) {
            logInfo("guilty")
            try {
                lineTripsRepository().reloadUiTrip(
                    uiTrip = previousTrip,
                    index = state.tripIndex,
                    referenceDateTime = state.referenceDateTime
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip ${previousTrip.tripId} for UI line " + "(${state.line?.lineId}, ${state.line?.type})",
                    e
                )
                null
            }
        }

        if (trip == null) {
            // keep previous trip intact, we don't want to hide information that we do have!
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                state.copy(error = true)
            }
            LineTripWidget().update(context, glanceId)
        } else {
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                state.copy(trip = trip)
            }
            LineTripWidget().update(context, glanceId)
        }
    }

    private suspend fun loadIndexNoFilterAsync(
        index: Int,
        state: WidgetState.LineTripsAvailable,
        context: Context,
        glanceId: GlanceId
    ): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                lineTripsRepository().getUiTrip(
                    lineId = state.line?.lineId!!,
                    lineType = state.line.type,
                    referenceDateTime = state.referenceDateTime,
                    index = index,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " + "(${state.line?.lineId}, ${state.line?.type})",
                    e
                )
                Pair(null, true /* <- useless when trip == null */)
            }
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.LineTripsAvailable) state.copy(
                trip = res.first,
                error = res.first == null
            ) else state
        }
        LineTripWidget().update(context, glanceId)
        logInfo("loadIndexNoFilterAsync")

        return res
    }

    private suspend fun loadIndexDirectionAsync(
        index: Int, prevState: WidgetState.LineTripsAvailable, context: Context, glanceId: GlanceId
    ): Pair<UiTrip?, Boolean> {
        val res = withContext(Dispatchers.IO) {
            try {
                lineTripsRepository().getUiTripWithDirection(
                    lineId = prevState.line?.lineId!!,
                    lineType = prevState.line.type,
                    referenceDateTime = prevState.referenceDateTime,
                    index = index,
                    direction = prevState.directionFilter,
                    prevIndex = prevState.tripIndex,
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for UI line " + "(${prevState.line?.lineId}, ${prevState.line?.type})" + "in direction ${prevState.directionFilter}",
                    e
                )
                null
            }
        }

        if (res == null) {
            // no trip could be loaded in the set direction, restore previous state,
            // but update prevEnabled or nextEnabled
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
                if (state is WidgetState.LineTripsAvailable) state.copy(
                    tripIndex = prevState.tripIndex,
                    trip = prevState.trip,
                    prevEnabled = if (index < prevState.tripIndex) false else prevState.prevEnabled,
                    nextEnabled = if (index > prevState.tripIndex) false else prevState.nextEnabled,
                ) else state
            }
            LineTripWidget().update(context, glanceId)

            return Pair(null, true /* <- useless when trip == null */)

        } else {
            val (trip, newIndex, loadedFromNetwork) = res
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
                if (state is WidgetState.LineTripsAvailable) state.copy(
                    tripIndex = newIndex,
                    trip = trip,
                    prevEnabled = newIndex > 0,
                    nextEnabled = newIndex < prevState.tripsInDayCount - 1
                ) else state
            }
            LineTripWidget().update(context, glanceId)

            return Pair(trip, loadedFromNetwork)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetStopTripsEntryPoint {
    fun stopsRepository(): StopsRepository
    fun tripsRepository(): StopTripsRepository
    fun historyDao(): HistoryDao

    suspend fun loadStop(
        stopId: Int,
        stopType: StopLineType,
        context: Context,
        glanceId: GlanceId
    ) {
        val stop = withContext(Dispatchers.IO) {
            try {
                stopsRepository().getDbStop(stopId, stopType).also {
                    if (it == null) {
                        logError(
                            "DB stop (${stopId}, ${stopType}) not found"
                        )
                    }

                    // register a view for this stop (assuming loadStop is called once)
                    historyDao().registerAccessed(false, stopId, stopType)
                }
            } catch (e: Throwable) {
                logError("Could not load DB stop (${stopId}, ${stopType})", e)
                null
            }
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
            if (stop == null) {
                WidgetState.Unavailable("Some error has occurred")
            } else {
                WidgetState.StopTripsAvailable(stop = stop)
            }
        }
    }

    suspend fun setReferenceDateTimeAsync(
        referenceDateTimeCurrentZone: ZonedDateTime,
        stopId: Int,
        stopType: StopLineType,
        context: Context,
        glanceId: GlanceId
    ) {
        val referenceDateTime = referenceDateTimeCurrentZone.withZoneSameInstant(ROME_ZONE_ID)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
            if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                tripIndex = 0,
                trip = null,
                prevEnabled = false,
                nextEnabled = false,
                referenceDateTime = referenceDateTime
            ) else oldState
        }
        LineTripWidget().update(context, glanceId)

        val tripsAtDateTimeList = withContext(Dispatchers.IO) {
            try {
                tripsRepository().getTrips(
                    stopId = stopId,
                    stopType = stopType,
                    referenceDateTime = referenceDateTime
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trips for DB stop (${stopId}, " +
                            "${stopType}) at time $referenceDateTime",
                    e
                )
                null
            }
        }

        if (tripsAtDateTimeList == null) {
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                if (oldState is WidgetState.StopTripsAvailable) oldState.copy(error = true) else oldState
            }
        } else {
            // show the first trip, which should be the next one arriving at the stop;
            // requestedByUser is false since the trip is surely up-to-date, as it was just fetched
            loadIndexAsync(0, false, stopId, stopType, context, glanceId)
        }
    }

    suspend fun loadIndex(
        index: Int,
        context: Context,
        glanceId: GlanceId,
        stopId: Int,
        stopType: StopLineType
    ) {
        val tripsAtDateTimeList = tripsRepository().getTrips(
            stopId = stopId, stopType = stopType,
            ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
        )
        if (index >= 0 && index < (tripsAtDateTimeList.tripCount)) {
            // only cancel any currently running job if there is something to do; the above
            // condition will be false e.g. when there are no trips in a day (but not only for that)
            loadIndexAsync(index, true, stopId, stopType, context, glanceId)
        }
    }

    private suspend fun loadIndexAsync(
        index: Int,
        requestedByUser: Boolean,
        stopId: Int,
        stopType: StopLineType,
        context: Context,
        glanceId: GlanceId
    ) {
        val tripsAtDateTimeList = tripsRepository().getTrips(
            stopId = stopId, stopType = stopType,
            ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
        )
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
            if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                tripIndex = index,
                trip = null,
                prevEnabled = index > 0,
                nextEnabled = index < (tripsAtDateTimeList.tripCount) - 1,
            ) else oldState
        }
        LineTripWidget().update(context, glanceId)

        val trip = withContext(Dispatchers.IO) {
            try {
                tripsAtDateTimeList.getUiTripAtIndex(index)
            } catch (e: Throwable) {
                logError(
                    "Could not load trip at index $index for DB stop " +
                            "(${stopId}, ${stopType})",
                    e
                )
                null
            }
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
            if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                trip = trip,
                loading = false,
                error = trip == null,
            ) else oldState
        }
        LineTripWidget().update(context, glanceId)

        if (requestedByUser && trip != null && trip.completedStops < trip.stopTimes.size) {
            // after showing the (possibly) outdated trip fast, reload it to show latest updates
            // (but reload it only if there actually is a trip and it is not completed)
            onReloadAsync(context, glanceId, stopId, stopType)
        }
    }


    suspend fun onReloadAsync(
        context: Context,
        glanceId: GlanceId,
        stopId: Int,
        stopType: StopLineType
    ) {
        val currentState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        if (currentState !is WidgetState.StopTripsAvailable) return
        val previousTrip = currentState.trip
        if (previousTrip == null) {
            // initial trips failed loading, try to load again the current day
            setReferenceDateTimeAsync(
                currentState.referenceDateTime,
                stopId,
                stopType,
                context,
                glanceId
            )
            return
        }
        val tripsAtDateTimeList = tripsRepository().getTrips(
            stopId = stopId, stopType = stopType,
            ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
        )

        val trip = withContext(Dispatchers.IO) {
            try {
                tripsAtDateTimeList.reloadUiTrip(
                    uiTrip = previousTrip,
                    index = currentState.tripIndex,
                    referenceDateTime = currentState.referenceDateTime
                )
            } catch (e: Throwable) {
                logError(
                    "Could not load trip ${previousTrip.tripId} for DB stop " +
                            "(${stopId}, ${stopType})",
                    e
                )
                null
            }
        }

        if (trip == null) {
            // keep previous trip intact, we don't want to hide information that we do have!
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                    error = true
                ) else oldState
            }
            LineTripWidget().update(context, glanceId)
        } else {
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                if (oldState is WidgetState.StopTripsAvailable) oldState.copy(
                    trip = trip
                ) else oldState
            }
            LineTripWidget().update(context, glanceId)
        }
    }
}

class NextTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context, glanceId: GlanceId, parameters: ActionParameters
    ) {
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        when (currentState) {
            is WidgetState.LineTripsAvailable -> {
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
                hiltEntryPoint.loadIndex(currentState.tripIndex + 1, glanceId, context)
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                    if (oldState is WidgetState.LineTripsAvailable) oldState.copy(loading = false) else oldState
                }
                LineTripWidget().update(context, glanceId)
            }

            is WidgetState.StopTripsAvailable -> {
                if (currentState.stop == null) return
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(
                        context,
                        WidgetStopTripsEntryPoint::class.java
                    )
                hiltEntryPoint.loadIndex(
                    currentState.tripIndex + 1,
                    context,
                    glanceId,
                    currentState.stop.stopId,
                    currentState.stop.type
                )
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                    if (oldState is WidgetState.StopTripsAvailable) oldState.copy(loading = false) else oldState
                }
                LineTripWidget().update(context, glanceId)
            }

            else -> {}
        }

        logInfo("NextTripAction performed")
    }
}

class PrevTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context, glanceId: GlanceId, parameters: ActionParameters
    ) {
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        when (currentState) {
            is WidgetState.LineTripsAvailable -> {
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
                hiltEntryPoint.loadIndex(currentState.tripIndex - 1, glanceId, context)
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                    if (oldState is WidgetState.LineTripsAvailable) oldState.copy(loading = false) else oldState
                }
                LineTripWidget().update(context, glanceId)
            }

            is WidgetState.StopTripsAvailable -> {
                if (currentState.stop == null) return
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(
                        context,
                        WidgetStopTripsEntryPoint::class.java
                    )
                hiltEntryPoint.loadIndex(
                    currentState.tripIndex - 1,
                    context,
                    glanceId,
                    currentState.stop.stopId,
                    currentState.stop.type
                )
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
                    if (oldState is WidgetState.StopTripsAvailable) oldState.copy(loading = false) else oldState
                }
                LineTripWidget().update(context, glanceId)
            }

            else -> {}
        }

        logInfo("PrevTripAction performed")
    }
}

class ReloadTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context, glanceId: GlanceId, parameters: ActionParameters
    ) {
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        when (currentState) {
            is WidgetState.LineTripsAvailable -> {
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                hiltEntryPoint.onReloadAsync(context, glanceId)
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
                    if (state is WidgetState.LineTripsAvailable) state.copy(loading = false) else state
                }
                LineTripWidget().update(context, glanceId)
            }

            is WidgetState.StopTripsAvailable -> {
                if (currentState.stop == null) return
                val hiltEntryPoint =
                    EntryPointAccessors.fromApplication(
                        context,
                        WidgetStopTripsEntryPoint::class.java
                    )
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(loading = true)
                }
                LineTripWidget().update(context, glanceId)
                hiltEntryPoint.onReloadAsync(
                    context,
                    glanceId,
                    currentState.stop.stopId,
                    currentState.stop.type
                )
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
                    if (state is WidgetState.StopTripsAvailable) state.copy(loading = false) else state
                }
                LineTripWidget().update(context, glanceId)
            }

            else -> {}
        }
    }
}

class ToggleDirectionAction : ActionCallback {
    override suspend fun onAction(
        context: Context, glanceId: GlanceId, parameters: ActionParameters
    ) {
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )

        if (currentState !is WidgetState.LineTripsAvailable) return
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val newDirectionFilter = when (currentState.directionFilter) {
            Direction.Forward -> Direction.Backward
            Direction.Backward -> Direction.ForwardAndBackward
            Direction.ForwardAndBackward -> Direction.Forward
        }
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
            currentState.copy(directionFilter = newDirectionFilter)
        }
        LineTripWidget().update(context, glanceId)

        if (newDirectionFilter == Direction.ForwardAndBackward) {
            if (currentState.trip == null) {
                // the trip can be null if there is no trip in that direction
                hiltEntryPoint.loadIndex(currentState.tripIndex, glanceId, context)
            } else {
                // no need to load the trip, as it's already loaded
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    currentState.copy(
                        prevEnabled = currentState.tripIndex > 0,
                        nextEnabled = currentState.tripIndex < currentState.tripsInDayCount - 1,
                        directionFilter = newDirectionFilter,
                    )
                }
                LineTripWidget().update(context, glanceId)
            }

        } else {
            if (currentState.trip?.direction != newDirectionFilter) {
                // we need to load another trip, since the current one has the wrong direction
                hiltEntryPoint.loadIndex(
                    currentState.tripIndex,
                    glanceId,
                    context
                )
            }
        }
        logInfo("ToggleDirectionAction performed, directionFilter: ${currentState.directionFilter}")
    }
}

class OnStopClickAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        logInfo("OnStopClickAction")
        val stopId = parameters[WidgetKeys.STOP_ID] ?: return
        val stopTypeRaw = parameters[WidgetKeys.STOP_TYPE] ?: return
        val stopType = StopLineType.valueOf(stopTypeRaw)
        logInfo("OnStopClickAction")
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )

        if (currentState is WidgetState.Unavailable) return
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetStopTripsEntryPoint::class.java)
        hiltEntryPoint.loadStop(stopId, stopType, context, glanceId)
        hiltEntryPoint.setReferenceDateTimeAsync(
            ZonedDateTime.now(),
            stopId,
            stopType,
            context,
            glanceId
        )

        LineTripWidget().update(context, glanceId)
    }
}

class ToggleShowPrevStop : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        logInfo("ToggleShowPrevStop")
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { oldState ->
            when (oldState) {
                is WidgetState.LineTripsAvailable -> oldState.copy(showPrevStop = !oldState.showPrevStop)
                is WidgetState.StopTripsAvailable -> oldState.copy(showPrevStop = !oldState.showPrevStop)
                else -> oldState
            }
        }
        LineTripWidget().update(context, glanceId)
    }
}