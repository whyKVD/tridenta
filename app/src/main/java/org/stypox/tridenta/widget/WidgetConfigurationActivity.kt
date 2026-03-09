package org.stypox.tridenta.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.tridenta.R
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.ui.lines.AreaChip
import org.stypox.tridenta.ui.lines.LineItem
import org.stypox.tridenta.ui.lines.LinesUiState
import org.stypox.tridenta.ui.lines.LinesViewModel
import org.stypox.tridenta.ui.lines.SelectAreaDialog
import org.stypox.tridenta.ui.theme.AppTheme
import org.stypox.tridenta.widget.actions.WidgetKeys

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
        setResult(Activity.RESULT_CANCELED)

        setContent {
            val linesViewModel: LinesViewModel = hiltViewModel()

            val linesUiState by linesViewModel.uiState.collectAsState()
            val lastReloadWasError by linesViewModel.lastReloadWasError.collectAsState()

            AppTheme {
                LineSelectionScreen(
                    criticalError = linesUiState.error,
                    minorError = lastReloadWasError,
                    loading = linesUiState.loading,
                    onReload = linesViewModel::onReload,
                    state = linesUiState,
                    onLineSelected = { selectedLine ->
                        saveWidgetConfiguration(selectedLine)
                    },
                    setSelectedArea = linesViewModel::setSelectedArea
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
                prefs.clear()
                prefs[WidgetKeys.LINE_ID] = line.lineId
                prefs[WidgetKeys.LINE_TYPE] = line.type.name
                prefs[WidgetKeys.DIRECTION_FILTER] = Direction.ForwardAndBackward.name
                prefs[WidgetKeys.IS_LOADING] = true
            }
            MyAppWidget().update(this@WidgetConfigurationActivity, glanceId)

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineSelectionScreen(
    criticalError: Boolean,
    minorError: Boolean,
    loading: Boolean,
    onReload: () -> Unit,
    state: LinesUiState,
    onLineSelected: (DbLine) -> Unit,
    setSelectedArea: (Area) -> Unit
) {
    var showAreaDialog by rememberSaveable { mutableStateOf(false) }
    if (showAreaDialog) {
        SelectAreaDialog(
            selectedArea = state.selectedArea,
            setSelectedArea = setSelectedArea,
            onDismiss = { showAreaDialog = false }
        )
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stringResource(R.string.selected_area))
                AreaChip(
                    area = state.selectedArea,
                    onClick = { showAreaDialog = true }
                )
            }
        })
    }) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = loading,
            state = rememberPullToRefreshState(),
            onRefresh = onReload,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxHeight()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                items(state.lines) { line ->
                    LineItem(
                        line, true,
                        modifier = Modifier.clickable { onLineSelected(line) },
                    )
                }
            }
        }
    }
}