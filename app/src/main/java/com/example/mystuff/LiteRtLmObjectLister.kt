package com.example.mystuff

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalApi::class)
class LiteRtLmObjectLister(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    suspend fun listObjects(modelFile: File, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val currentEngine = getOrCreateEngine(modelFile)
        val prompt = """
            Identify every distinct visible physical object in this cropped photo.
            Return only a concise bullet list.
            Use one object per line.
            For multiple objects of the same type, use plural in one line. 
            Do not include visible people.
            If an object is uncertain, prefix it with "possible".
            If an object cannot be identified, ignore it.
            Do not describe the scene, the surface and do not add extra commentary.
        """.trimIndent()

        currentEngine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.1),
                systemInstruction = Contents.of(
                    "You are a careful visual object listing assistant. Only list objects visible in the image."
                ),
            )
        ).use { conversation ->
            val response = conversation.sendMessage(
                Contents.of(
                    listOf(
                        Content.ImageBytes(bitmap.toPngByteArray()),
                        Content.Text(prompt),
                    )
                )
            )
            response.toString().trim()
        }
    }

    @Synchronized
    private fun getOrCreateEngine(modelFile: File): Engine {
        val path = modelFile.absolutePath
        engine?.let { existing ->
            if (loadedModelPath == path) return existing
            existing.close()
        }

        val cacheDir = File(appContext.cacheDir, "litertlm").apply { mkdirs() }
        val newEngine = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                maxNumTokens = 1024,
                cacheDir = cacheDir.absolutePath,
            )
        )
        newEngine.initialize()
        engine = newEngine
        loadedModelPath = path
        return newEngine
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
        loadedModelPath = null
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
