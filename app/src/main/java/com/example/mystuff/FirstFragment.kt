package com.example.mystuff

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.mystuff.databinding.FragmentFirstBinding
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val objectLister by lazy { LiteRtLmObjectLister(requireContext()) }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var currentBitmap: Bitmap? = null

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        copySelectedModel(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonImportModel.setOnClickListener {
            importModel.launch(arrayOf("application/octet-stream", "*/*"))
        }
        binding.buttonAnalyzePhoto.setOnClickListener {
            analyzeCurrentPhoto()
        }
        binding.buttonSaveObjectList.setOnClickListener {
            saveObjectList()
        }
        binding.buttonCancelObjectList.setOnClickListener {
            clearEditableObjectList()
        }

        parentFragmentManager.setFragmentResultListener(
            MainActivity.CROPPED_PHOTO_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val uriString = bundle.getString(MainActivity.CROPPED_PHOTO_URI_KEY) ?: return@setFragmentResultListener
            loadCroppedPhoto(uriString.toUri())
        }

        updateModelStatus()
        updateAnalyzeButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        objectLister.close()
        super.onDestroy()
    }

    private fun copySelectedModel(uri: Uri) {
        objectLister.close()
        setBusy(true, getString(R.string.status_importing_model))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val targetFile = getModelFile()
                    val targetDir = targetFile.parentFile ?: error("Missing model directory")
                    targetDir.mkdirs()
                    val tempFile = File(targetDir, "${targetFile.name}.tmp")

                    requireContext().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to open selected model" }
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (targetFile.exists() && !targetFile.delete()) {
                        error("Unable to replace existing model")
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        error("Unable to finish model import")
                    }
                    targetFile.length()
                }
            }

            var startedAnalysis = false
            result
                .onSuccess {
                    updateModelStatus()
                    setResultText(getString(R.string.status_model_ready))
                    currentBitmap?.let {
                        startedAnalysis = true
                        analyzeBitmap(it)
                    }
                }
                .onFailure { throwable ->
                    updateModelStatus()
                    setResultText(
                        getString(
                            R.string.error_model_import_failed,
                            throwable.message ?: throwable::class.java.simpleName
                        )
                    )
                }
            if (!startedAnalysis) {
                setBusy(false)
                updateAnalyzeButton()
            }
        }
    }

    private fun loadCroppedPhoto(uri: Uri) {
        setBusy(true, getString(R.string.status_loading_photo))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { decodeBitmap(uri) }
            }

            var startedAnalysis = false
            result
                .onSuccess { bitmap ->
                    currentBitmap = bitmap
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.textPhotoStatus.text = getString(R.string.status_photo_ready)
                    updateAnalyzeButton()
                    if (getModelFile().exists()) {
                        startedAnalysis = true
                        analyzeBitmap(bitmap)
                    } else {
                        setResultText(getString(R.string.status_import_model_first))
                    }
                }
                .onFailure { throwable ->
                    binding.textPhotoStatus.text = getString(R.string.status_photo_missing)
                    setResultText(
                        getString(
                            R.string.error_photo_load_failed,
                            throwable.message ?: throwable::class.java.simpleName
                        )
                    )
                    updateAnalyzeButton()
                }

            if (!startedAnalysis) {
                setBusy(false)
            }
        }
    }

    private fun analyzeCurrentPhoto() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            setResultText(getString(R.string.status_take_photo_first))
            return
        }
        analyzeBitmap(bitmap)
    }

    private fun analyzeBitmap(bitmap: Bitmap) {
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            setResultText(getString(R.string.status_import_model_first))
            updateAnalyzeButton()
            return
        }

        setBusy(true, getString(R.string.status_analyzing_photo))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                objectLister.listObjects(modelFile, bitmap)
            }

            result
                .onSuccess { objects ->
                    setResultText(objects, editable = true)
                }
                .onFailure { throwable ->
                    setResultText(
                        getString(
                            R.string.error_analysis_failed,
                            throwable.message ?: throwable::class.java.simpleName
                        )
                    )
                }
            setBusy(false)
            updateAnalyzeButton()
        }
    }

    private fun saveObjectList() {
        val objectList = binding.textResult.text.toString()
        val stuffDocument = mapOf(
            "description" to objectList,
            "location" to STUFF_LOCATION,
            "createdAt" to FieldValue.serverTimestamp(),
        )

        setObjectListActionsEnabled(false)
        firestore.collection(STUFF_COLLECTION)
            .add(stuffDocument)
            .addOnCompleteListener { task ->
                val currentBinding = _binding ?: return@addOnCompleteListener
                val safeContext = context ?: return@addOnCompleteListener
                if (task.isSuccessful) {
                    Toast.makeText(
                        safeContext,
                        R.string.status_object_list_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val throwable = task.exception
                    Toast.makeText(
                        safeContext,
                        getString(
                            R.string.error_object_list_save_failed,
                            throwable?.message ?: getString(R.string.error_unknown)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                setObjectListActionsEnabled(currentBinding.textResult.isEnabled)
            }
    }

    private fun clearEditableObjectList() {
        if (binding.textResult.isEnabled) {
            binding.textResult.text.clear()
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(requireContext().contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxSide = maxOf(info.size.width, info.size.height)
            var sampleSize = 1
            while (maxSide / sampleSize > MAX_IMAGE_SIDE_PX) {
                sampleSize *= 2
            }
            decoder.setTargetSampleSize(sampleSize)
        }
    }

    private fun getModelFile(): File {
        return File(File(requireContext().filesDir, "models"), MODEL_FILE_NAME)
    }

    private fun updateModelStatus() {
        val modelFile = getModelFile()
        binding.textModelStatus.text = if (modelFile.exists()) {
            getString(R.string.status_model_installed, formatBytes(modelFile.length()))
        } else {
            getString(R.string.status_model_missing)
        }
    }

    private fun updateAnalyzeButton() {
        binding.buttonAnalyzePhoto.isEnabled = currentBitmap != null && getModelFile().exists()
    }

    private fun setBusy(isBusy: Boolean, message: String? = null) {
        binding.progressAnalyzing.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.buttonAnalyzePhoto.isEnabled = !isBusy && currentBitmap != null && getModelFile().exists()
        binding.buttonImportModel.isEnabled = !isBusy
        message?.let { setResultText(it) }
    }

    private fun setResultText(text: CharSequence, editable: Boolean = false) {
        binding.textResult.setText(text)
        binding.textResult.isEnabled = editable
        binding.textResult.isCursorVisible = editable
        binding.textResult.isFocusable = editable
        binding.textResult.isFocusableInTouchMode = editable
        binding.objectListActions.visibility = if (editable) View.VISIBLE else View.GONE
        setObjectListActionsEnabled(editable)
        if (editable) {
            binding.textResult.setSelection(binding.textResult.text.length)
        }
    }

    private fun setObjectListActionsEnabled(enabled: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.buttonSaveObjectList.isEnabled = enabled
        currentBinding.buttonCancelObjectList.isEnabled = enabled
    }

    private fun formatBytes(bytes: Long): String {
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
        private const val MODEL_FILE_NAME = "object_lister.litertlm"
        private const val STUFF_COLLECTION = "stuff"
        private const val STUFF_LOCATION = "house"
        private const val MAX_IMAGE_SIDE_PX = 1024
    }
}
