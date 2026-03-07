package org.stypox.tridenta.widget.actions

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object WidgetKeys {
    val REFRESH_TIMESTAMP = longPreferencesKey("refresh_timestamp")
    val TRIP_INDEX = intPreferencesKey("trip_index")
    val LINE_ID = intPreferencesKey("line_id")
    val TRIPS_IN_DAY_COUNT = intPreferencesKey("trips_in_day_count")
    val PREV_TRIP_INDEX = intPreferencesKey("prev_trip_index")

    // Save booleans
    val IS_LOADING = booleanPreferencesKey("is_loading")
    val IS_INITIAL_DATA_LOADED = booleanPreferencesKey("is_initial_data_loaded")
    val HAS_ERROR = booleanPreferencesKey("has_error")
    val PREV_ENABLED = booleanPreferencesKey("prev_enabled")
    val NEXT_ENABLED = booleanPreferencesKey("next_enabled")

    // Save strings (Useful for Enums, IDs, or serialized JSON)
    val LINE_TYPE = stringPreferencesKey("line_type") // e.g., "Urban", "Suburban"
    val DIRECTION_FILTER = stringPreferencesKey("direction_filter") // e.g., "Forward", "Backward"
}