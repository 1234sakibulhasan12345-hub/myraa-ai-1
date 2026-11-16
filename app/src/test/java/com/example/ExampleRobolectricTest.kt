package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AiModelProvider
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.GeminiFirebaseProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.TaskCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.RoutingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Myraa AI", appName)
  }

  @Test
  fun `firestore database id resource is configured`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbId = context.getString(R.string.firestore_database_id)
    assertFalse(dbId.isBlank())
    assertEquals(
      "ai-studio-android-myraaai-e14a6757-ff2e-4f5e-b497-fef3a378e9b6",
      dbId
    )
  }

  @Test
  fun `vision attachment overrides ultra fast policy to multimodal flash`() {
    val registry = ModelCapabilityRegistry(listOf<AiModelProvider>(GeminiFirebaseProvider()))
    val router = ModelRouter(registry)
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    val decision = router.selectModel(
      BrainGenerationRequest(
        prompt = "Check screen layout",
        languagePreference = BrainLanguage.EN,
        routingPolicy = RoutingPolicy.ULTRA_FAST,
        projectContextSummary = "",
        recentHistory = emptyList(),
        attachedBitmap = bitmap
      )
    )

    assertEquals(TaskCategory.VISION_ANALYSIS, decision.taskCategory)
    assertEquals(ModelRouter.MODEL_GEMINI_FLASH, decision.selectedModel.modelId)
  }
}
