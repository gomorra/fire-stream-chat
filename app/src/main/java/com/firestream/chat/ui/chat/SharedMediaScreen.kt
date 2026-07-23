package com.firestream.chat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.firestream.chat.ui.components.ScaledImageDecoder
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedMediaScreen(
    onBackClick: () -> Unit,
    viewModel: SharedMediaViewModel = hiltViewModel()
) {
    val media by viewModel.media.collectAsState()
    val context = LocalContext.current
    // Index into `media` of the tapped tile; the swipeable gallery pager opens
    // there. Just the index so the open image survives activity recreation.
    var fullscreenIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(enabled = fullscreenIndex != null) { fullscreenIndex = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared Media") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (media.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No shared media",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                itemsIndexed(media) { index, item ->
                    // Prefer the on-disk full file when present, else the remote
                    // URL — the same synchronous resolution rememberMessageImageModel
                    // and FullscreenImageViewer use. decoderFactory routes the tile
                    // through ImageDecoder (see ScaledImageDecoder): large old
                    // originals subsample to a black bitmap under Coil's default
                    // BitmapFactory path, which was rendering those tiles black while
                    // the barely-downsampled fullscreen decode of the same image works.
                    val request = remember(item) {
                        val localFile = item.localUri
                            ?.let { File(it) }
                            ?.takeIf { it.exists() && it.isFile && it.canRead() }
                        ImageRequest.Builder(context)
                            .data(localFile ?: item.mediaUrl)
                            .decoderFactory(ScaledImageDecoder.Factory())
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        error = rememberVectorPainter(Icons.Default.BrokenImage),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { fullscreenIndex = index }
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = fullscreenIndex != null, enter = fadeIn(), exit = fadeOut()) {
        fullscreenIndex?.let { idx ->
            FullscreenImagePager(
                items = media.map { FullscreenMediaItem(it.mediaUrl, it.localUri) },
                initialIndex = idx,
                onDismiss = { fullscreenIndex = null },
            )
        }
    }
}
