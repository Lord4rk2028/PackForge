package com.packforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Skeleton placeholder para texto (línea simple).
 * Usa shimmerLoading() de AnimationModifiers.kt
 */
@Composable
fun SkeletonText(
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .shimmerLoading()
    )
}

/**
 * Skeleton placeholder para título
 */
@Composable
fun SkeletonTitle(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp
) {
    SkeletonText(
        modifier = modifier,
        width = width,
        height = 24.dp,
        shape = RoundedCornerShape(6.dp)
    )
}

/**
 * Skeleton placeholder para círculo (como avatar o ícono)
 */
@Composable
fun SkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .shimmerLoading()
    )
}

/**
 * Skeleton placeholder para tarjeta
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .shimmerLoading()
    )
}

/**
 * Skeleton para lista de elementos (varias líneas)
 */
@Composable
fun SkeletonListItem(
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
    lines: Int = 2
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showAvatar) {
            SkeletonCircle(size = 48.dp)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            repeat(lines) { index ->
                SkeletonText(
                    width = if (index == lines - 1) 100.dp else Dp.Infinity,
                    height = 14.dp
                )
            }
        }
    }
}

/**
 * Skeleton para Grid de elementos (tipo Minecraft crafting)
 */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    columns: Int = 3,
    rows: Int = 3
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .shimmerLoading()
                    )
                }
            }
        }
    }
}

/**
 * Skeleton completo para pantalla de importación
 */
@Composable
fun ImportScreenSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .shimmerLoading()
        )
        SkeletonGrid(columns = 3, rows = 3)
        SkeletonCard(height = 100.dp)
        repeat(3) {
            SkeletonListItem(showAvatar = true, lines = 2)
        }
    }
}

/**
 * Skeleton para pantalla de búsqueda
 */
@Composable
fun SearchScreenSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .shimmerLoading()
        )
        repeat(6) {
            SkeletonCard(height = 160.dp)
        }
    }
}
