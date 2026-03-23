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
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.log.logWarning
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
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
        if (state !is WidgetState.Available) return
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
            if (s is WidgetState.Available) s.copy(loading = false) else s
        }
        LineTripWidget().update(context, glanceId)
    }

    private suspend fun loadIndexAsync(
        index: Int,
        state: WidgetState.Available,
        context: Context,
        glanceId: GlanceId
    ) {
        // hide the current trip, as it's going to change
        val prevState = state
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.Available) state.copy(
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
        state: WidgetState.Available,
        context: Context,
        glanceId: GlanceId
    ) {
        val referenceDateTime = referenceDateTimeCurrentZone.withZoneSameInstant(ROME_ZONE_ID)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.Available) state.copy(
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
            if (state is WidgetState.Available) state.copy(
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
        if (state !is WidgetState.Available) return
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
        state: WidgetState.Available,
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
            if (state is WidgetState.Available) state.copy(
                trip = res.first,
                error = res.first == null
            ) else state
        }
        LineTripWidget().update(context, glanceId)
        logInfo("loadIndexNoFilterAsync")

        return res
    }

    private suspend fun loadIndexDirectionAsync(
        index: Int, prevState: WidgetState.Available, context: Context, glanceId: GlanceId
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
                if (state is WidgetState.Available) state.copy(
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
                if (state is WidgetState.Available) state.copy(
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

class NextTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context, glanceId: GlanceId, parameters: ActionParameters
    ) {
        val currentState: WidgetState = getAppWidgetState(
            context,
            LineTripWidgetStateDefinition, glanceId
        )
        if (currentState !is WidgetState.Available) return
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        hiltEntryPoint.loadIndex(currentState.tripIndex + 1, glanceId, context)

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
        if (currentState !is WidgetState.Available) return
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        hiltEntryPoint.loadIndex(currentState.tripIndex - 1, glanceId, context)

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
        if (currentState !is WidgetState.Available) return
        val hiltEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
            currentState.copy(loading = true)
        }
        LineTripWidget().update(context, glanceId)
        hiltEntryPoint.onReloadAsync(context, glanceId)
        updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) { state ->
            if (state is WidgetState.Available) state.copy(loading = false) else state
        }
        LineTripWidget().update(context, glanceId)
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

        if (currentState !is WidgetState.Available) return
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
            val state = currentState
            if (state.trip == null) {
                // the trip can be null if there is no trip in that direction
                hiltEntryPoint.loadIndex(state.tripIndex, glanceId, context)
            } else {
                // no need to load the trip, as it's already loaded
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    state.copy(
                        prevEnabled = state.tripIndex > 0,
                        nextEnabled = state.tripIndex < state.tripsInDayCount - 1,
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