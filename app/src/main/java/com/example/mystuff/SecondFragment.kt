package com.example.mystuff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.mystuff.ui.theme.AccentCopper
import com.example.mystuff.ui.theme.AccentSage
import com.example.mystuff.ui.theme.BackgroundObsidian
import com.example.mystuff.ui.theme.BorderSteel
import com.example.mystuff.ui.theme.MyStuffTheme
import com.example.mystuff.ui.theme.SurfaceObsidian
import com.example.mystuff.ui.theme.TextLimestone
import com.example.mystuff.ui.theme.TextStoneGray
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecondFragment : Fragment() {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val timestampFormatter by lazy {
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    }

    private var listenerRegistration: ListenerRegistration? = null
    private var activeImagePath: String? = null
    private var uiState by mutableStateOf(StuffDetailsUiState())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MyStuffTheme {
                    StuffDetailsScreen(
                        uiState = uiState,
                        onBack = { findNavController().navigateUp() },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val stuffId = arguments?.getString(ARG_STUFF_ID)
        if (stuffId.isNullOrBlank()) {
            uiState = StuffDetailsUiState(
                isLoading = false,
                errorMessage = getString(R.string.details_no_item_selected),
            )
            return
        }

        observeStuffDetails(stuffId)
    }

    override fun onDestroyView() {
        listenerRegistration?.remove()
        listenerRegistration = null
        activeImagePath = null
        uiState = StuffDetailsUiState()
        super.onDestroyView()
    }

    private fun observeStuffDetails(stuffId: String) {
        uiState = StuffDetailsUiState(isLoading = true)
        listenerRegistration = firestore.collection(STUFF_COLLECTION)
            .document(stuffId)
            .addSnapshotListener { snapshot, throwable ->
                if (throwable != null) {
                    uiState = StuffDetailsUiState(
                        isLoading = false,
                        errorMessage = getString(
                            R.string.details_load_failed,
                            throwable.message ?: throwable::class.java.simpleName,
                        ),
                    )
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    uiState = StuffDetailsUiState(
                        isLoading = false,
                        errorMessage = getString(R.string.details_item_missing),
                    )
                    return@addSnapshotListener
                }

                val details = snapshot.toStuffDetails()
                val keepBitmap = activeImagePath == details.imagePath && uiState.imageBitmap != null
                uiState = StuffDetailsUiState(
                    isLoading = false,
                    item = details,
                    imageBitmap = if (keepBitmap) uiState.imageBitmap else null,
                    isImageLoading = details.imagePath.isNotBlank() && !keepBitmap,
                )

                if (details.imagePath.isNotBlank() && !keepBitmap) {
                    loadDetailImage(details.imagePath)
                }
            }
    }

    private fun loadDetailImage(imagePath: String) {
        if (activeImagePath == imagePath && uiState.isImageLoading) return

        activeImagePath = imagePath
        uiState = uiState.copy(
            imageBitmap = null,
            isImageLoading = true,
            imageErrorMessage = null,
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = Tasks.await(
                        storage.reference.child(imagePath).getBytes(MAX_DETAIL_IMAGE_BYTES)
                    )
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Unable to decode saved image")
                }
            }

            if (view == null || activeImagePath != imagePath) return@launch

            uiState = result.fold(
                onSuccess = { bitmap ->
                    uiState.copy(
                        imageBitmap = bitmap,
                        isImageLoading = false,
                        imageErrorMessage = null,
                    )
                },
                onFailure = { throwable ->
                    uiState.copy(
                        isImageLoading = false,
                        imageErrorMessage = getString(
                            R.string.details_image_unavailable,
                            throwable.message ?: throwable::class.java.simpleName,
                        ),
                    )
                },
            )
        }
    }

    private fun DocumentSnapshot.toStuffDetails(): StuffDetails {
        val description = getString("description").orEmpty()
        val objects = description.lineSequence()
            .map(::cleanObjectLine)
            .filter(String::isNotBlank)
            .toList()
        val createdAt = getTimestamp("createdAt")?.toDate()

        return StuffDetails(
            id = id,
            title = objects.firstOrNull()?.toDisplayTitle() ?: getString(R.string.details_default_title),
            status = if (createdAt == null) {
                getString(R.string.details_status_syncing)
            } else {
                getString(R.string.details_status_saved)
            },
            description = description,
            objects = objects,
            location = getString("location") ?: getString(R.string.details_pending_value),
            ownerUid = getString("ownerUid") ?: getString(R.string.details_pending_value),
            createdAt = createdAt?.let(timestampFormatter::format)
                ?: getString(R.string.details_pending_value),
            imagePath = getString("imagePath").orEmpty(),
            dimensions = formatDimensions(getLong("imageWidth"), getLong("imageHeight")),
            imageSize = formatBytes(getLong("imageSizeBytes")),
        )
    }

    private fun cleanObjectLine(line: String): String {
        var cleaned = line.trim()
        while (cleaned.startsWith("-") || cleaned.startsWith("*")) {
            cleaned = cleaned.drop(1).trim()
        }
        return cleaned
    }

    private fun String.toDisplayTitle(): String {
        return replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }

    private fun formatDimensions(width: Long?, height: Long?): String {
        if (width == null || height == null) return getString(R.string.details_pending_value)
        return getString(R.string.details_dimensions, width, height)
    }

    private fun formatBytes(bytes: Long?): String {
        if (bytes == null) return getString(R.string.details_pending_value)
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    companion object {
        const val ARG_STUFF_ID = "stuffId"
        private const val STUFF_COLLECTION = "stuff"
        private const val MAX_DETAIL_IMAGE_BYTES = 12L * 1024L * 1024L

        fun createArgs(stuffId: String): Bundle {
            return Bundle().apply {
                putString(ARG_STUFF_ID, stuffId)
            }
        }
    }
}

@Composable
private fun StuffDetailsScreen(
    uiState: StuffDetailsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundObsidian),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailsHeader(
                    status = uiState.item?.status ?: stringResource(R.string.details_status_syncing),
                    onBack = onBack,
                )
            }

            when {
                uiState.isLoading -> item { LoadingPanel() }
                uiState.errorMessage != null -> item {
                    EmptyPanel(message = uiState.errorMessage)
                }
                uiState.item != null -> {
                    item {
                        DetailImagePanel(
                            bitmap = uiState.imageBitmap,
                            isLoading = uiState.isImageLoading,
                            errorMessage = uiState.imageErrorMessage,
                        )
                    }
                    item {
                        TitlePanel(item = uiState.item)
                    }
                    item {
                        LedgerSection(item = uiState.item)
                    }
                    item {
                        ObjectListSection(objects = uiState.item.objects)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsHeader(
    status: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = SurfaceObsidian,
            border = BorderStroke(1.dp, BorderSteel),
            contentColor = TextLimestone,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_media_previous),
                    contentDescription = stringResource(R.string.details_back_content_description),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.details_header_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextLimestone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.details_header_kicker),
                style = MaterialTheme.typography.labelSmall,
                color = AccentSage,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusChip(text = status)
    }
}

@Composable
private fun DetailImagePanel(
    bitmap: Bitmap?,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceObsidian,
        border = BorderStroke(1.dp, BorderSteel),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.details_image_content_description),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = AccentCopper,
                    strokeWidth = 2.dp,
                )
                else -> EmptyPanelContent(
                    message = errorMessage ?: stringResource(R.string.details_no_image),
                )
            }
        }
    }
}

@Composable
private fun TitlePanel(item: StuffDetails, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceObsidian,
        border = BorderStroke(1.dp, BorderSteel),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextLimestone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.description.ifBlank { stringResource(R.string.details_empty_objects) },
                style = MaterialTheme.typography.bodyMedium,
                color = TextStoneGray,
            )
        }
    }
}

@Composable
private fun LedgerSection(item: StuffDetails, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(text = stringResource(R.string.details_ledger_title))
        LedgerRow(
            label = stringResource(R.string.details_created_label),
            value = item.createdAt,
        )
        LedgerRow(
            label = stringResource(R.string.details_location_label),
            value = item.location,
        )
        LedgerRow(
            label = stringResource(R.string.details_image_label),
            value = item.dimensions,
        )
        LedgerRow(
            label = stringResource(R.string.details_size_label),
            value = item.imageSize,
        )
        LedgerRow(
            label = stringResource(R.string.details_owner_label),
            value = item.ownerUid,
        )
    }
}

@Composable
private fun ObjectListSection(objects: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(text = stringResource(R.string.details_objects_title))
        if (objects.isEmpty()) {
            SurfaceRow {
                Text(
                    text = stringResource(R.string.details_empty_objects),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextStoneGray,
                )
            }
        } else {
            objects.forEach { objectName ->
                SurfaceRow {
                    Text(
                        text = objectName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextLimestone,
                    )
                    StatusChip(text = stringResource(R.string.details_object_status))
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(label: String, value: String, modifier: Modifier = Modifier) {
    SurfaceRow(modifier = modifier) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodyMedium,
            color = TextLimestone,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SurfaceRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceObsidian,
        border = BorderStroke(1.dp, BorderSteel),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun LoadingPanel(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceObsidian,
        border = BorderStroke(1.dp, BorderSteel),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = AccentCopper,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun EmptyPanel(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceObsidian,
        border = BorderStroke(1.dp, BorderSteel),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyPanelContent(message = message)
        }
    }
}

@Composable
private fun EmptyPanelContent(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = TextStoneGray,
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = AccentSage,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = AccentCopper.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, AccentCopper.copy(alpha = 0.3f)),
        contentColor = AccentCopper,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class StuffDetailsUiState(
    val isLoading: Boolean = true,
    val item: StuffDetails? = null,
    val imageBitmap: Bitmap? = null,
    val isImageLoading: Boolean = false,
    val errorMessage: String? = null,
    val imageErrorMessage: String? = null,
)

private data class StuffDetails(
    val id: String,
    val title: String,
    val status: String,
    val description: String,
    val objects: List<String>,
    val location: String,
    val ownerUid: String,
    val createdAt: String,
    val imagePath: String,
    val dimensions: String,
    val imageSize: String,
)
