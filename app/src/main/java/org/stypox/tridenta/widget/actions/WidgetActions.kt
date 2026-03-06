package org.stypox.tridenta.widget.actions

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.stypox.tridenta.db.LineDao
import org.stypox.tridenta.db.StopDao
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
    fun lineDao(): LineDao
    // Add HistoryDao and LinesRepository here too
}

// 2. Refactor: onNextClicked() -> NextTripAction
class NextTripAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val tripsRepo = hiltEntryPoint.lineTripsRepository()

        // 1. Read current state (tripIndex) from Glance Preferences
        updateAppWidgetState(context, glanceId) { prefs ->
            val currentIndex = prefs[WidgetKeys.TRIP_INDEX] ?: 0
            // TODO: Actually retrive the next index for the given trip
            val nextIndex = currentIndex + 1

            // 3. Update the state in preferences
            prefs[WidgetKeys.TRIP_INDEX] = nextIndex
            // Save other necessary UI state strings/booleans
        }

        // 4. Force the widget to redraw with the new state
        MyAppWidget().updateAll(context)
        logInfo("NextTripAction performed")
    }
}

// 3. Refactor: onPrevClicked() -> PrevTripAction
class PrevTripAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val tripsRepo = hiltEntryPoint.lineTripsRepository()

        /*updateAppWidgetState(context, glanceId) { prefs ->
            val currentIndex = prefs[WidgetKeys.TRIP_INDEX] ?: 0
            if (currentIndex > 0) {
                val prevIndex = currentIndex - 1
                // Fetch new trip and save to prefs...
                prefs[WidgetKeys.TRIP_INDEX] = prevIndex
            }
        }
        TridentaWidget().update(context, glanceId)*/
        logInfo("PrevTripAction performed")
    }
}

// 4. Refactor: onReload() -> ReloadTripAction
class ReloadTripAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MyAppWidget().updateAll(context)
    }
}

// 5. Refactor: onDirectionClicked() -> ToggleDirectionAction
class ToggleDirectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Read current direction from prefs, toggle it, fetch new trip, update prefs
        updateAppWidgetState(context,glanceId) { prefs ->
            val actualDirectionFilter = prefs[WidgetKeys.DIRECTION_FILTER]
            val newDirectionFilter = when (Direction.valueOf(actualDirectionFilter ?: Direction.ForwardAndBackward.name)) {
                Direction.Forward -> Direction.Backward
                Direction.Backward -> Direction.ForwardAndBackward
                Direction.ForwardAndBackward -> Direction.Forward
            }
            prefs[WidgetKeys.DIRECTION_FILTER] = newDirectionFilter.name
        }
        MyAppWidget().updateAll(context)
    }
}