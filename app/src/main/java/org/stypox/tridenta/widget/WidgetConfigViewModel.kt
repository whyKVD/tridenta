package org.stypox.tridenta.widget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.tridenta.db.LineDao
import org.stypox.tridenta.db.data.DbLine
import javax.inject.Inject
import kotlin.collections.emptyList

@HiltViewModel
class WidgetConfigViewModel @Inject constructor(
    private val lineDao: LineDao
) : ViewModel() {
    // 1. Create a Mutable internal state
    private val _availableLines = MutableStateFlow<List<DbLine>>(emptyList())
    // 2. Expose it as a read-only StateFlow for the UI
    val availableLines = _availableLines.asStateFlow()

    init {
        // 3. Fetch the data once when the ViewModel is created
        viewModelScope.launch {
            // Because your Dao is a suspend function, we can call it here
            withContext(Dispatchers.IO) {
                val lines = lineDao.getAllLines()
                _availableLines.value = lines
            }
        }
    }
}