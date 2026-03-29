package org.stypox.tridenta.widget.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.stypox.tridenta.R
import org.stypox.tridenta.db.data.DbStop
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.widget.theme.SmallCircularProgressIndicatorGlance

@Composable
fun StopTripsScreenGlance(
    stop: DbStop?,
    trip: UiTrip?,
    error: Boolean,
    loading: Boolean,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    onReloadAction: Action,
    onPrevAction: Action,
    onNextAction: Action,
    onLineClickAction: Action,
    isFavorite: Boolean,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background)
    ) {
        StopAppBar(stop, isFavorite, onLineClickAction)
        TripViewGlance(
            trip = trip,
            error = error,
            loading = loading,
            onReloadAction = onReloadAction,
            onPrevAction = onPrevAction,
            onNextAction = onNextAction,
            prevEnabled = prevEnabled,
            nextEnabled = nextEnabled,
            stopIdToHighlight = stop?.stopId,
            stopTypeToHighlight = stop?.type
        )
    }
}

@Composable
fun StopAppBar(
    stop: DbStop?,
    isFavorite: Boolean,
    onLineClickAction: Action,
) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.drawable.menu),
            contentDescription = "menu",
            modifier = GlanceModifier.clickable(onLineClickAction).padding(end = 8.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
        if (stop == null) {
            SmallCircularProgressIndicatorGlance()
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stop.name,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurface)
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())

        Image(
            provider = ImageProvider(
                if (isFavorite) R.drawable.favorite_filled else R.drawable.favorite
            ),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
            contentDescription = context.getString(R.string.favorite),
        )
    }
}