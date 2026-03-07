package org.stypox.tridenta.widget.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.layout.size
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview
@Composable
fun SmallCircularProgressIndicatorGlance(modifier: GlanceModifier = GlanceModifier) {
    CircularProgressIndicator(
        color = GlanceTheme.colors.onSurface,
        modifier = modifier.size(16.dp),
    )
}