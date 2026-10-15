// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.nv.pair.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = HarborTeal,
    onPrimary = HarborBackground,
    secondary = Color(0xFF78B8D1),
    secondaryContainer = Color(0xFF16463F),
    onSecondaryContainer = Color(0xFFD4FFF6),
    tertiary = Color(0xFFD9B85C),
    background = HarborBackground,
    surface = HarborSurface,
    surfaceVariant = HarborSurfaceVariant,
    surfaceContainerLowest = Color(0xFF091B27),
    surfaceContainerLow = HarborSurface,
    surfaceContainer = Color(0xFF122C3B),
    surfaceContainerHigh = HarborSurfaceVariant,
    surfaceContainerHighest = Color(0xFF1B394A),
    onSurface = Color(0xFFEAF2F5),
    onSurfaceVariant = Color(0xFFADC0CA),
    error = HarborError,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B60),
    secondary = Color(0xFF35677A),
    tertiary = Color(0xFF806000),
)

@Composable
fun PAIRTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
