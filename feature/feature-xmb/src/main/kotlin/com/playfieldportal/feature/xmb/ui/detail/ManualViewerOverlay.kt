package com.playfieldportal.feature.xmb.ui.detail

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.menuCursorEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * In-app PDF manual viewer — Android's [PdfRenderer], no external apps or libraries.
 *
 * Input: D-pad LEFT/RIGHT turn pages, UP/DOWN scroll within the page, B closes (all routed by
 * the ViewModel through [page]/[scrollSteps]); touch taps the left/right screen thirds to turn
 * pages, drags to scroll, ✕ to close.
 *
 * One page is rendered at a time (fit-to-width, off the main thread); [PdfRenderer] is not
 * thread-safe, so rendering is confined to the renderer's monitor.
 */
@Composable
fun ManualViewerOverlay(
    source: String,                   // content:// document or internal file path
    title: String,
    page: Int,
    scrollSteps: Int,
    onPageCount: (Int) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val renderer = remember(source) { mutableStateOf<PdfRenderer?>(null) }
    val failed = remember(source) { mutableStateOf(false) }

    DisposableEffect(source) {
        val pfd = runCatching {
            if (source.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
            } else {
                ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }.onFailure { Timber.w(it, "Manual open failed") }.getOrNull()
        val opened = runCatching { pfd?.let { PdfRenderer(it) } }
            .onFailure { Timber.w(it, "Manual is not a renderable PDF") }.getOrNull()
        renderer.value = opened
        failed.value = opened == null
        opened?.let { onPageCount(it.pageCount) }
        onDispose {
            renderer.value = null
            // Close under the same monitor the render path holds, so a page render in flight
            // finishes before the renderer is torn down.
            runCatching { opened?.let { synchronized(it) { it.close() } } }
            runCatching { pfd?.close() }
        }
    }

    val pageBitmap by produceState<Bitmap?>(initialValue = null, renderer.value, page) {
        val pdf = renderer.value ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                synchronized(pdf) {
                    pdf.openPage(page.coerceIn(0, pdf.pageCount - 1)).use { p ->
                        val scale = RENDER_WIDTH_PX.toFloat() / p.width.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(
                            RENDER_WIDTH_PX,
                            (p.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)   // PDF pages assume white paper
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }.onFailure { Timber.w(it, "Manual page render failed") }.getOrNull()
        }
    }

    val scrollState = rememberScrollState()
    val stepPx = with(LocalDensity.current) { 140.dp.roundToPx() }
    LaunchedEffect(page) { scrollState.scrollTo(0) }
    LaunchedEffect(scrollSteps) {
        scrollState.animateScrollTo((scrollSteps * stepPx).coerceAtMost(scrollState.maxValue))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            // Tap zones: outer thirds turn pages; center is neutral so drags/reading aren't
            // hijacked. Registered on the container so scrolling still works.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    when {
                        offset.x < size.width / 3f -> onPrevPage()
                        offset.x > size.width * 2f / 3f -> onNextPage()
                    }
                }
            },
    ) {
        when {
            failed.value -> Text(
                "This manual could not be displayed.",
                color = Color(0xFFFF8A8A),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            pageBitmap == null -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = menuCursorEdge(),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = 44.dp, bottom = 30.dp),
            ) {
                Image(
                    bitmap = pageBitmap!!.asImageBitmap(),
                    contentDescription = "$title — manual page ${page + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Header: title + page counter + close. Kept on top of the page content.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color(0xFFEEEEEE), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pdf = renderer.value
                if (pdf != null) {
                    Text("${page + 1} / ${pdf.pageCount}", color = Color(0xAAEEEEEE), fontSize = 12.sp)
                }
                Text(
                    "  ✕  ",
                    color = Color(0xFFEEEEEE),
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(start = 12.dp),
                )
            }
        }

        Text(
            "◀ ▶  Page   •   ▲ ▼  Scroll   •   B  Close",
            color = Color(0x66EEEEEE),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

// Fit-to-width render resolution: crisp on the 1080p-class handheld screens PFP targets while
// keeping a page bitmap ~15 MB worst case; only one page is held at a time.
private const val RENDER_WIDTH_PX = 1600
