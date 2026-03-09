package org.stypox.tridenta.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.tridenta.db.HistoryDao
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.log.logInfo
import org.stypox.tridenta.repo.LineTripsRepository
import org.stypox.tridenta.repo.LinesRepository
import org.stypox.tridenta.widget.MyAppWidget

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun lineTripsRepository(): LineTripsRepository
    fun lineRepository(): LinesRepository
    fun historyDao(): HistoryDao
    // Add HistoryDao and LinesRepository here too
}

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

            prefs[WidgetKeys.PREV_ENABLED] = nextIndex > 0
            prefs[WidgetKeys.NEXT_ENABLED] = nextIndex < tripsInDayCount - 1
            prefs[WidgetKeys.PREV_TRIP_INDEX] = currentIndex
            prefs[WidgetKeys.TRIP_INDEX] = nextIndex
        }

        MyAppWidget().update(context, glanceId)
        logInfo("NextTripAction performed")
    }
}

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

            prefs[WidgetKeys.PREV_ENABLED] = nextIndex > 0
            prefs[WidgetKeys.NEXT_ENABLED] = nextIndex < tripsInDayCount - 1
            prefs[WidgetKeys.PREV_TRIP_INDEX] = currentIndex
            prefs[WidgetKeys.TRIP_INDEX] = nextIndex
        }

        MyAppWidget().update(context, glanceId)
        logInfo("PrevTripAction performed")
    }
}

class ReloadTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetKeys.REFRESH_TIMESTAMP] = System.currentTimeMillis()
        }
        MyAppWidget().update(context, glanceId)
    }
}

class ToggleDirectionAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
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
        MyAppWidget().update(context, glanceId)
        logInfo("ToggleDirectionAction performed")
    }
}