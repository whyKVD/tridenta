package org.stypox.tridenta.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.extractor.ROME_ZONE_ID
import org.stypox.tridenta.log.logError
import org.stypox.tridenta.ui.lines.LinesViewModel
import org.stypox.tridenta.ui.theme.AppTheme
import org.stypox.tridenta.widget.actions.WidgetEntryPoint
import org.stypox.tridenta.widget.ui.LineSelectionScreenWidget
import java.time.ZonedDateTime

@AndroidEntryPoint
class WidgetConfigurationActivity :
    ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the Widget ID from the Intent that launched this Activity
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If the intent doesn't have a valid ID, bail out early.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Set the result to CANCELED right away. This ensures that if the user backs
        // out of the activity without picking a line, the widget is removed from the home screen.
        setResult(RESULT_CANCELED)

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        LineSelectionScreenWidget(
                            onLineSelected = { selectedLine ->
                                saveWidgetConfiguration(selectedLine)
                            },
                        )
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveWidgetConfiguration(line: DbLine) {
        val context = applicationContext

        // We use GlobalScope or a dedicated CoroutineScope because the activity
        // will finish immediately, and we need this save to complete.
        GlobalScope.launch(Dispatchers.IO) {
            // 2. Map the standard Android appWidgetId to a Jetpack GlanceId
            val glanceManager = GlanceAppWidgetManager(context)
            val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
            val hiltEntryPoint =
                EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
            val line = withContext(Dispatchers.IO) {
                try {
                    hiltEntryPoint.lineRepository().getUiLine(line.lineId, line.type).also {
                        if (it == null) {
                            logError(
                                "UI line (${line.lineId}, ${line.type}) not found"
                            )
                        }

                        // register a view for this line (assuming loadLine is called once)
                        hiltEntryPoint.historyDao().registerAccessed(true, line.lineId, line.type)
                    }
                } catch (e: Throwable) {
                    logError("Could not load UI line (${line.lineId}, ${line.type})", e)
                    null
                }
            }
            if (line == null) {
                updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                    WidgetState.Unavailable(message = "Something went wrong")
                }
                return@launch
            }
            val tripsRepository = hiltEntryPoint.lineTripsRepository()
            val referenceDateTime = ZonedDateTime.now().withZoneSameInstant(ROME_ZONE_ID)
            val (tripsInDayCount, tripIndex, trip) = tripsRepository.getUiTrip(
                line.lineId,
                line.type,
                referenceDateTime,
                Direction.ForwardAndBackward
            )

            // 3. Save the selected data to this specific widget's Preferences
            updateAppWidgetState(context, LineTripWidgetStateDefinition, glanceId) {
                WidgetState.LineTripsAvailable(
                    line,
                    trip,
                    ZonedDateTime.now(),
                    tripsInDayCount,
                    tripIndex,
                    prevEnabled = tripIndex > 0,
                    nextEnabled = tripIndex < tripsInDayCount - 1,
                    loading = false,
                )
            }
            LineTripWidget().update(this@WidgetConfigurationActivity, glanceId)

            // 5. Tell the Android OS that the configuration was successful
            withContext(Dispatchers.Main) {
                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(RESULT_OK, resultValue)
                finish()
            }
        }
    }
}