package com.example.mystuff

import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.mystuff.databinding.FragmentFirstBinding
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private val objectLister by lazy { LiteRtLmObjectLister(requireContext()) }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val credentialManager by lazy { CredentialManager.create(requireContext()) }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private var currentBitmap: Bitmap? = null
    private var authInProgress = false
    private var workInProgress = false
    private var authStatusMessageResId: Int? = null
    private val authStateListener = FirebaseAuth.AuthStateListener { updateAuthUi() }

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        copySelectedModel(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonGoogleAuth.setOnClickListener {
            if (auth.currentUser == null) {
                signInWithGoogle()
            } else {
                signOut()
            }
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
            viewLifecycleOwner,
        ) { _, bundle ->
            val uriString = bundle.getString(MainActivity.CROPPED_PHOTO_URI_KEY) ?: return@setFragmentResultListener
            loadCroppedPhoto(uriString.toUri())
        }

        updateAuthUi()
        updateAnalyzeButton()
    }

    fun launchModelImport() {
        if (workInProgress) return

        importModel.launch(arrayOf("application/octet-stream", "*/*"))
    }

    override fun onStart() {
        super.onStart()
        auth.addAuthStateListener(authStateListener)
        updateAuthUi()
    }

    override fun onStop() {
        auth.removeAuthStateListener(authStateListener)
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        objectLister.close()
        super.onDestroy()
    }

    private fun signInWithGoogle() {
        if (authInProgress) return

        setAuthBusy(isBusy = true, statusMessageResId = R.string.status_signing_in)
        viewLifecycleOwner.lifecycleScope.launch {
            val credentialResult = runCatching {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts = false)
                    .setServerClientId(getString(R.string.default_web_client_id))
                    .setAutoSelectEnabled(autoSelectEnabled = true)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                credentialManager.getCredential(requireContext(), request).credential
            }

            credentialResult
                .onSuccess { credential ->
                    runCatching { getGoogleIdToken(credential) }
                        .onSuccess { idToken ->
                            firebaseAuthWithGoogle(idToken)
                        }
                        .onFailure { throwable ->
                            setAuthBusy(isBusy = false)
                            showAuthError(throwable)
                        }
                }
                .onFailure { throwable ->
                    setAuthBusy(isBusy = false)
                    if (throwable is GetCredentialCancellationException) {
                        showToast(R.string.status_sign_in_cancelled)
                    } else {
                        showAuthError(throwable)
                    }
                }
        }
    }

    private fun getGoogleIdToken(credential: Credential): String {
        if (
            (credential is CustomCredential) &&
            (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        error("Credential is not a Google ID token")
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                setAuthBusy(false)
                if (task.isSuccessful) {
                    updateAuthUi()
                    showToast(R.string.status_signed_in)
                } else {
                    showAuthError(task.exception ?: IllegalStateException("Unknown error"))
                }
            }
    }

    private fun signOut() {
        if (authInProgress) return

        setAuthBusy(isBusy = true, statusMessageResId = R.string.status_signing_out)
        auth.signOut()
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { throwable ->
                Log.w(TAG, "Couldn't clear credential state", throwable)
            }
            setAuthBusy(isBusy = false)
            showToast(R.string.status_signed_out)
        }
    }

    private fun updateAuthUi() {
        val currentBinding = _binding ?: return
        val user = auth.currentUser
        currentBinding.buttonGoogleAuth.isEnabled = !authInProgress
        currentBinding.buttonGoogleAuth.setText(
            if (user == null) R.string.action_sign_in_with_google else R.string.action_sign_out,
        )
        currentBinding.textAuthStatus.text = authStatusMessageResId?.let { getString(it) }
            ?: getAuthStatusText(user)
    }

    private fun getAuthStatusText(user: FirebaseUser?): String {
        if (user == null) {
            return getString(R.string.auth_status_signed_out)
        }

        val displayName = user.displayName ?: user.email ?: user.uid
        return getString(R.string.auth_status_signed_in, displayName)
    }

    private fun setAuthBusy(isBusy: Boolean, statusMessageResId: Int? = null) {
        authInProgress = isBusy
        authStatusMessageResId = if (isBusy) statusMessageResId else null
        updateAuthUi()
    }

    private fun showAuthError(throwable: Throwable) {
        val safeContext = context ?: return
        Toast.makeText(
            safeContext,
            safeContext.getString(
                R.string.error_auth_failed,
                throwable.message ?: throwable::class.java.simpleName,
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showToast(messageResId: Int) {
        context?.let { safeContext ->
            Toast.makeText(safeContext, messageResId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copySelectedModel(uri: Uri) {
        objectLister.close()
        setBusy(isBusy = true, message = getString(R.string.status_importing_model))
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
                    setResultText(getString(R.string.status_model_ready))
                    currentBitmap?.let {
                        startedAnalysis = true
                        analyzeBitmap(it)
                    }
                }
                .onFailure { throwable ->
                    setResultText(
                        getString(
                            R.string.error_model_import_failed,
                            throwable.message ?: throwable::class.java.simpleName,
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
                            throwable.message ?: throwable::class.java.simpleName,
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
                            throwable.message ?: throwable::class.java.simpleName,
                        )
                    )
                }
            setBusy(false)
            updateAnalyzeButton()
        }
    }

    private fun saveObjectList() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.status_take_photo_first, Toast.LENGTH_SHORT).show()
            return
        }
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), R.string.status_sign_in_to_save, Toast.LENGTH_SHORT).show()
            signInWithGoogle()
            return
        }

        val objectList = binding.textResult.text.toString()
        val documentRef = firestore.collection(STUFF_COLLECTION).document()
        val imagePath = "$STUFF_COLLECTION/${documentRef.id}.webp"
        val thumbnailPath = "$STUFF_COLLECTION/${documentRef.id}_thumb.webp"

        setObjectListActionsEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val encodedImagesResult = runCatching {
                withContext(Dispatchers.Default) {
                    encodeStuffImages(bitmap)
                }
            }

            encodedImagesResult
                .onSuccess { encodedImages ->
                    val metadata = StorageMetadata.Builder()
                        .setContentType(WEBP_CONTENT_TYPE)
                        .build()
                    val imageRef = storage.reference.child(imagePath)
                    val thumbnailRef = storage.reference.child(thumbnailPath)
                    val stuffDocument = mapOf(
                        "description" to objectList,
                        "location" to STUFF_LOCATION,
                        "ownerUid" to user.uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "imagePath" to imagePath,
                        "thumbnailPath" to thumbnailPath,
                        "imageWidth" to encodedImages.image.width,
                        "imageHeight" to encodedImages.image.height,
                        "thumbnailWidth" to encodedImages.thumbnail.width,
                        "thumbnailHeight" to encodedImages.thumbnail.height,
                        "imageContentType" to WEBP_CONTENT_TYPE,
                        "imageSizeBytes" to encodedImages.image.bytes.size,
                        "thumbnailSizeBytes" to encodedImages.thumbnail.bytes.size,
                    )

                    val imageUploadTask = imageRef.putBytes(encodedImages.image.bytes, metadata)
                    val thumbnailUploadTask = thumbnailRef.putBytes(encodedImages.thumbnail.bytes, metadata)

                    Tasks.whenAll(imageUploadTask, thumbnailUploadTask)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) {
                                task.exception?.let { throw it }
                                error("Image upload failed")
                            }
                            documentRef.set(stuffDocument)
                        }
                        .addOnCompleteListener { task ->
                            val currentBinding = _binding ?: return@addOnCompleteListener
                            val safeContext = context
                            val shouldNavigateToDetails = task.isSuccessful
                            if (safeContext != null) {
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
                            }
                            setObjectListActionsEnabled(currentBinding.textResult.isEnabled)
                            if (shouldNavigateToDetails && isAdded) {
                                findNavController().navigate(
                                    R.id.action_FirstFragment_to_SecondFragment,
                                    SecondFragment.createArgs(documentRef.id),
                                )
                            }
                        }
                }
                .onFailure { throwable ->
                    val currentBinding = _binding ?: return@onFailure
                    context?.let { safeContext ->
                        Toast.makeText(
                            safeContext,
                            getString(
                                R.string.error_object_list_save_failed,
                                throwable.message ?: getString(R.string.error_unknown)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    setObjectListActionsEnabled(currentBinding.textResult.isEnabled)
                }
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
            if (maxSide > DISPLAY_IMAGE_MAX_SIDE_PX) {
                val scale = DISPLAY_IMAGE_MAX_SIDE_PX.toDouble() / maxSide
                val targetWidth = (info.size.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (info.size.height * scale).roundToInt().coerceAtLeast(1)
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        }
    }

    private fun encodeStuffImages(bitmap: Bitmap): EncodedStuffImages {
        val displayBitmap = scaleBitmapToMaxSide(bitmap)
        val thumbnailBitmap = centerCropBitmap(bitmap)
        return try {
            EncodedStuffImages(
                image = EncodedImage(
                    bytes = displayBitmap.toWebpByteArray(DISPLAY_IMAGE_WEBP_QUALITY),
                    width = displayBitmap.width,
                    height = displayBitmap.height
                ),
                thumbnail = EncodedImage(
                    bytes = thumbnailBitmap.toWebpByteArray(THUMBNAIL_WEBP_QUALITY),
                    width = thumbnailBitmap.width,
                    height = thumbnailBitmap.height
                )
            )
        } finally {
            if (displayBitmap !== bitmap) {
                displayBitmap.recycle()
            }
            if (thumbnailBitmap !== bitmap) {
                thumbnailBitmap.recycle()
            }
        }
    }

    private fun scaleBitmapToMaxSide(bitmap: Bitmap, maxSidePx: Int = DISPLAY_IMAGE_MAX_SIDE_PX): Bitmap {
        val currentMaxSide = maxOf(bitmap.width, bitmap.height)
        if (currentMaxSide <= maxSidePx) {
            return bitmap
        }

        val scale = maxSidePx.toDouble() / currentMaxSide
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight, true)
    }

    private fun centerCropBitmap(bitmap: Bitmap, sizePx: Int = THUMBNAIL_IMAGE_SIDE_PX): Bitmap {
        val cropSide = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - cropSide) / 2
        val top = (bitmap.height - cropSide) / 2
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropSide, cropSide)
        if ((cropped.width == sizePx) && (cropped.height == sizePx)) {
            return cropped
        }

        val scaled = cropped.scale(sizePx, sizePx, true)
        if ((scaled !== cropped) && (cropped !== bitmap)) {
            cropped.recycle()
        }
        return scaled
    }

    private fun Bitmap.toWebpByteArray(quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        check(compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)) {
            "Unable to encode WebP image"
        }
        return stream.toByteArray()
    }

    private fun getModelFile(): File {
        return File(File(requireContext().filesDir, "models"), MODEL_FILE_NAME)
    }

    private fun updateAnalyzeButton() {
        binding.buttonAnalyzePhoto.isEnabled = (currentBitmap != null) && getModelFile().exists()
    }

    private fun setBusy(isBusy: Boolean, message: String? = null) {
        workInProgress = isBusy
        binding.progressAnalyzing.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.buttonAnalyzePhoto.isEnabled = !isBusy && (currentBitmap != null) && getModelFile().exists()
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

    private class EncodedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncodedImage

            if (!bytes.contentEquals(other.bytes)) return false
            if (width != other.width) return false

            return height == other.height
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }
    }

    private data class EncodedStuffImages(
        val image: EncodedImage,
        val thumbnail: EncodedImage
    )

    companion object {
        private const val TAG = "FirstFragment"
        private const val MODEL_FILE_NAME = "object_lister.litertlm"
        private const val STUFF_COLLECTION = "stuff"
        private const val STUFF_LOCATION = "root"
        private const val DISPLAY_IMAGE_MAX_SIDE_PX = 1280
        private const val THUMBNAIL_IMAGE_SIDE_PX = 256
        private const val DISPLAY_IMAGE_WEBP_QUALITY = 82
        private const val THUMBNAIL_WEBP_QUALITY = 76
        private const val WEBP_CONTENT_TYPE = "image/webp"
    }
}
