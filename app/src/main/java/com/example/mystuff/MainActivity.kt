package com.example.mystuff

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment
import com.example.mystuff.ui.theme.AccentCopper
import com.example.mystuff.ui.theme.BackgroundObsidian
import com.example.mystuff.ui.theme.BorderSteel
import com.example.mystuff.ui.theme.MyStuffTheme
import com.example.mystuff.ui.theme.TextLimestone
import com.example.mystuff.ui.theme.TextStoneGray
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val CROPPED_PHOTO_REQUEST_KEY = "cropped_photo_request"
        const val CROPPED_PHOTO_URI_KEY = "cropped_photo_uri"
    }

    private lateinit var photoUri: Uri

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            launchCrop(photoUri)
        }
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            showMessage("Photo cropped!")
            publishCroppedPhoto(photoUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyStuffTheme {
                StuffDashboard(
                    modifier = Modifier.fillMaxSize(),
                    onAddStuff = {
                        photoUri = getTmpFileUri()
                        takePicture.launch(photoUri)
                    },
                    onImportModel = ::requestModelImport,
                    fragmentManager = supportFragmentManager,
                )
            }
        }
    }

    private fun launchCrop(uri: Uri) {
        val intent = Intent("com.android.camera.action.CROP")
        intent.setDataAndType(uri, "image/*")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.putExtra("crop", "true")
        intent.putExtra("aspectX", 0)
        intent.putExtra("aspectY", 0)
        intent.putExtra("scale", true)
        intent.putExtra("scaleUpIfNeeded", true)
        intent.putExtra("noFaceDetection", true)
        intent.putExtra("return-data", false)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)

        try {
            cropImage.launch(intent)
        } catch (_: Exception) {
            showMessage("Crop not supported; using full photo")
            publishCroppedPhoto(uri)
        }
    }

    private fun publishCroppedPhoto(uri: Uri) {
        val result = Bundle().apply {
            putString(CROPPED_PHOTO_URI_KEY, uri.toString())
        }
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        val resultFragmentManager = navHostFragment?.childFragmentManager ?: supportFragmentManager
        resultFragmentManager.setFragmentResult(CROPPED_PHOTO_REQUEST_KEY, result)
    }

    private fun getTmpFileUri(): Uri {
        val tmpFile = File(File(cacheDir, "images").apply { mkdirs() }, "tmp_image.jpg")
        return FileProvider.getUriForFile(applicationContext, "$packageName.fileprovider", tmpFile)
    }

    private fun requestModelImport() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        val firstFragment =
            navHostFragment?.childFragmentManager?.primaryNavigationFragment as? FirstFragment

        if (firstFragment == null) {
            showMessage(getString(R.string.status_model_import_unavailable))
            return
        }

        firstFragment.launchModelImport()
    }

    private fun showMessage(message: String) {
        Snackbar.make(findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
    }
}

@Composable
fun StuffDashboard(
    modifier: Modifier = Modifier,
    onAddStuff: () -> Unit = {},
    onImportModel: () -> Unit = {},
    fragmentManager: FragmentManager? = null,
) {
    val addContentDescription = stringResource(R.string.add)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddStuff,
                containerColor = AccentCopper,
                contentColor = BackgroundObsidian,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_camera),
                    contentDescription = addContentDescription,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderSection(
                title = "My Stuff",
                onImportModel = onImportModel,
            )
            StatCardsSection()
            StuffGridList(
                fragmentManager = fragmentManager,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun HeaderSection(
    title: String,
    modifier: Modifier = Modifier,
    onImportModel: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        UserAvatarMenu(onImportModel = onImportModel)
    }
}

@Composable
private fun UserAvatarMenu(
    modifier: Modifier = Modifier,
    onImportModel: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = AccentCopper,
            contentColor = BackgroundObsidian,
        ) {
            IconButton(onClick = { expanded = true }) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = BackgroundObsidian,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_import_model)) },
                onClick = {
                    expanded = false
                    onImportModel()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_profile)) },
                onClick = { expanded = false },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_settings)) },
                onClick = { expanded = false },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.action_sign_out)) },
                onClick = { expanded = false },
            )
        }
    }
}

@Composable
fun StatCardsSection(modifier: Modifier = Modifier) {
    val stats = listOf(
        DashboardStat(label = "Capture", value = "Crop"),
        DashboardStat(label = "Analyze", value = "Local"),
        DashboardStat(label = "Save", value = "Cloud"),
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stats.forEach { stat ->
            StatCard(
                stat = stat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun StuffGridList(
    modifier: Modifier = Modifier,
    fragmentManager: FragmentManager? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, BorderSteel),
        tonalElevation = 1.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
        ) {
            if (fragmentManager == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FragmentContainerView(context).apply {
                            id = R.id.nav_host_fragment_content_main
                        }
                    },
                    update = { container ->
                        if (fragmentManager.findFragmentById(container.id) == null) {
                            fragmentManager.commit {
                                setReorderingAllowed(true)
                                replace(container.id, NavHostFragment.create(R.navigation.nav_graph))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatCard(stat: DashboardStat, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(78.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, BorderSteel),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelMedium,
                color = TextStoneGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stat.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextLimestone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class DashboardStat(
    val label: String,
    val value: String,
)
