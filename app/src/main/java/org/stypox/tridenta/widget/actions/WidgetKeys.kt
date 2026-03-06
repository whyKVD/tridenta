package org.stypox.tridenta.widget.actions

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId

object WidgetKeys {
    val TRIP_INDEX = intPreferencesKey("trip_index")
    val LINE_ID = intPreferencesKey("line_id")
    val TRIPS_IN_DAY_COUNT = intPreferencesKey("trips_in_day_count")

    // Save booleans
    val IS_LOADING = booleanPreferencesKey("is_loading")
    val IS_INITIAL_DATA_LOADED = booleanPreferencesKey("is_initial_data_loaded")
    val HAS_ERROR = booleanPreferencesKey("has_error")
    val PREV_ENABLED = booleanPreferencesKey("prev_enabled")
    val NEXT_ENABLED = booleanPreferencesKey("next_enabled")

    // Save strings (Useful for Enums, IDs, or serialized JSON)
    val LINE_TYPE = stringPreferencesKey("line_type") // e.g., "Urban", "Suburban"
    val DIRECTION_FILTER = stringPreferencesKey("direction_filter") // e.g., "Forward", "Backward"

    // Complex objects like UiTrip cannot be saved directly in simple Preferences.
    // You either have to serialize the UiTrip to a JSON string, or just save the tripId
    // and let the Widget fetch it from the database every time it updates.
    val CURRENT_TRIP_ID = stringPreferencesKey("current_trip_id")
}