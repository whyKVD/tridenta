package org.stypox.tridenta.widget.ui

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
import androidx.hilt.navigation.compose.hiltViewModel
import com.ramcosta.composedestinations.annotation.DeepLink
import com.ramcosta.composedestinations.annotation.Destination
import org.stypox.tridenta.R
import org.stypox.tridenta.db.data.DbLine
import org.stypox.tridenta.enums.Area
import org.stypox.tridenta.ui.lines.AreaChip
import org.stypox.tridenta.ui.lines.LineItem
import org.stypox.tridenta.ui.lines.LinesUiState
import org.stypox.tridenta.ui.lines.LinesViewModel
import org.stypox.tridenta.ui.lines.SelectAreaDialog
import org.stypox.tridenta.ui.nav.DEEP_LINK_URL_PATTERN

@Destination(
    deepLinks = [DeepLink(uriPattern = DEEP_LINK_URL_PATTERN)]
)
@Composable
fun LineSelectionScreenWidget(
    onLineSelected: (DbLine) -> Unit,
) {
    val linesViewModel: LinesViewModel = hiltViewModel()

    val linesUiState by linesViewModel.uiState.collectAsState()
    val lastReloadWasError by linesViewModel.lastReloadWasError.collectAsState()
    return LineSelectionScreenWidget(
        criticalError = linesUiState.error,
        minorError = lastReloadWasError,
        loading = linesUiState.loading,
        onReload = linesViewModel::onReload,
        state = linesUiState,
        onLineSelected = onLineSelected,
        setSelectedArea = linesViewModel::setSelectedArea
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineSelectionScreenWidget(
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