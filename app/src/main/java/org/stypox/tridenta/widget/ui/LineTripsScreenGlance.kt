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
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.stypox.tridenta.R
import org.stypox.tridenta.enums.Direction
import org.stypox.tridenta.repo.data.UiLine
import org.stypox.tridenta.repo.data.UiTrip
import org.stypox.tridenta.util.textColorOnBackground
import org.stypox.tridenta.util.toLineColor
import org.stypox.tridenta.widget.theme.SmallCircularProgressIndicatorGlance

@Composable
fun LineTripsWidgetScreen(
    line: UiLine?,
    trip: UiTrip?,
    error: Boolean,
    loading: Boolean,
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    isFavorite: Boolean,
    directionFilter: Direction,
    onReloadAction: Action,
    onPrevAction: Action,
    onNextAction: Action,
    onLineClickAction: Action,
    onDirectionClickAction: Action
) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background)
    ) {
        LineAppBar(
            line = line,
            isFavorite = isFavorite,
            directionFilter = directionFilter,
            onDirectionAction = onDirectionClickAction,
            onLineClickAction = onLineClickAction,
        )
        TripViewGlance(
            trip = trip,
            error = error,
            loading = loading,
            onReloadAction = onReloadAction,
            onPrevAction = onPrevAction,
            onNextAction = onNextAction,
            prevEnabled = prevEnabled,
            nextEnabled = nextEnabled,
            stopIdToHighlight = null,
            stopTypeToHighlight = null
        )
    }
}

@Composable
fun LineAppBar(
    line: UiLine?,
    isFavorite: Boolean,
    directionFilter: Direction,
    onDirectionAction: Action,
    onLineClickAction: Action,
) {
    val context = LocalContext.current
    // The main container Row
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

        // 1. The Title (Left Side)
        // defaultWeight() makes the text take up all leftover space,
        // pushing the icons to the far right.

        if (line == null) {
            SmallCircularProgressIndicatorGlance()
        } else {
            val shortNameBackground = line.color.toLineColor()
            val textColor = textColorOnBackground(shortNameBackground)
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.trips_for_line),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(GlanceModifier.size(8.dp))
                Box(
                    modifier = GlanceModifier
                        .background(shortNameBackground)
                        .padding(8.dp)
                ) {
                    Text(
                        text = line.shortName,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(day = textColor, night = textColor),
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())

        // 2. The Action Icons (Right Side)
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // News/Warning Icon
            if (line != null && line.newsItems.isNotEmpty()) {
                Image(
                    provider = ImageProvider(R.drawable.warning_filled),
                    contentDescription = "News",
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier
                        .padding(end = 8.dp)
                )
            }

            // Direction Toggle Icon
            Image(
                provider = ImageProvider(getDirectionDrawable(directionFilter)),
                contentDescription = "Toggle Direction",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .padding(end = 8.dp)
                    .clickable(onDirectionAction)
            )

            // Favorite Toggle Icon
            Image(
                provider = ImageProvider(
                    if (isFavorite) R.drawable.favorite_filled else R.drawable.favorite
                ),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                contentDescription = context.getString(R.string.favorite),
            )
        }
    }
}

// Helper to resolve drawables for Glance
private fun getDirectionDrawable(direction: Direction): Int {
    return when (direction) {
        Direction.Forward -> R.drawable.turn_sharp_right
        Direction.Backward -> R.drawable.u_turn_left
        Direction.ForwardAndBackward -> R.drawable.swap_calls
    }
}