package org.stypox.tridenta.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.ui.lines.LineItem
import org.stypox.tridenta.ui.lines.LinesViewModel
import org.stypox.tridenta.ui.theme.TitleText
import org.stypox.tridenta.widget.actions.WidgetKeys

@AndroidEntryPoint
class WidgetConfigurationActivity:
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
        setResult(Activity.RESULT_CANCELED)

        setContent {
            val viewModel: LinesViewModel = hiltViewModel()

            // 2. Collect the complex UI state
            val uiState by viewModel.uiState.collectAsState()

            if (uiState.loading) {
                CircularProgressIndicator() // Show loading spinner
            } else if (uiState.error) {
                Text("Error loading lines. Please try again.")
            } else {
                // 4. Pass the loaded lines to your selection screen
                LineSelectionScreen(
                    lines = uiState.lines,
                    onLineSelected = { selectedLine ->
                        saveWidgetConfiguration(selectedLine)
                    }
                )
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

            // 3. Save the selected data to this specific widget's Preferences
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetKeys.LINE_ID] = line.lineId
                prefs[WidgetKeys.LINE_TYPE] = line.type.name
                prefs[WidgetKeys.IS_INITIAL_DATA_LOADED] = true
            }
            MyAppWidget().updateAll(this@WidgetConfigurationActivity)

            // 5. Tell the Android OS that the configuration was successful
            withContext(Dispatchers.Main) {
                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(Activity.RESULT_OK, resultValue)
                finish()
            }
        }
    }
}

@Composable
fun LineSelectionScreen(lines: List<DbLine>, onLineSelected: (DbLine) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TitleText("Seleziona la linea:") }
        items(lines) { line ->
            LineItem(
                line, true,
                modifier = Modifier.clickable { onLineSelected(line) },
            )
        }
    }
}