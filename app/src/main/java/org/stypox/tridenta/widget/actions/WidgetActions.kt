package org.stypox.tridenta.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.tridenta.db.LineDao
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.widget.MyAppWidget

// 1. Create a Hilt Entry point to access your Repositories inside Glance Actions
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun lineTripsRepository(): LineTripsRepository
    fun lineRepository(): LinesRepository
    // Add HistoryDao and LinesRepository here too
}

// 2. Refactor: onNextClicked() -> NextTripAction
class NextTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val currentIndex = prefs[WidgetKeys.TRIP_INDEX] ?: 0
            val tripsInDayCount = prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] ?: 0
            val nextIndex = currentIndex + 1
            if (nextIndex !in 0..<tripsInDayCount) {
                return@updateAppWidgetState
            }

            prefs[WidgetKeys.PREV_TRIP_INDEX] = currentIndex
            prefs[WidgetKeys.TRIP_INDEX] = nextIndex
        }

        MyAppWidget().updateAll(context)
        logInfo("NextTripAction performed")
    }
}

// 3. Refactor: onPrevClicked() -> PrevTripAction
class PrevTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val currentIndex = prefs[WidgetKeys.TRIP_INDEX] ?: 0
            val tripsInDayCount = prefs[WidgetKeys.TRIPS_IN_DAY_COUNT] ?: 0
            val nextIndex = currentIndex - 1
            if (nextIndex !in 0..<tripsInDayCount) {
                return@updateAppWidgetState
            }

            prefs[WidgetKeys.PREV_TRIP_INDEX] = currentIndex
            prefs[WidgetKeys.TRIP_INDEX] = nextIndex
        }

        MyAppWidget().updateAll(context)
        logInfo("PrevTripAction performed")
    }
}

// 4. Refactor: onReload() -> ReloadTripAction
class ReloadTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
        }
        MyAppWidget().updateAll(context)
    }
}

// 5. Refactor: onDirectionClicked() -> ToggleDirectionAction
class ToggleDirectionAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // Read current direction from prefs, toggle it, fetch new trip, update prefs
        updateAppWidgetState(context, glanceId) { prefs ->
            val actualDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            val newDirectionFilter = when (Direction.valueOf(
                actualDirectionFilter ?: Direction.ForwardAndBackward.name
            )) {
                Direction.Forward -> Direction.Backward
                Direction.Backward -> Direction.ForwardAndBackward
                Direction.ForwardAndBackward -> Direction.Forward
            }
            prefs[WidgetKeys.DIRECTION_FILTER] = newDirectionFilter.name
        }
        MyAppWidget().updateAll(context)
    }
}

class ToggleFavoriteAction: ActionCallback{
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        TODO("Not yet implemented")
    }
}