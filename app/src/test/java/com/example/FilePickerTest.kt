package com.example

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.example.ui.components.FilePicker
import com.example.ui.components.formatFileSizeLabel
import com.example.ui.theme.ToneForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilePickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun filePickerLaunchesGetContentAudioAndDeliversSelectedUri() {
        val expectedUri = Uri.parse("content://media/external/audio/media/42")
        var capturedInputMime: String? = null
        var deliveredUri: Uri? = null

        val fakeRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                capturedInputMime = input as? String
                dispatchResult(requestCode, expectedUri)
            }
        }
        val fakeOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = fakeRegistry
        }

        composeTestRule.setContent {
            ToneForgeTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner) {
                    FilePicker(
                        onFileSelected = { uri -> deliveredUri = uri }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("file_picker_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_audio_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_audio_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("audio/*", capturedInputMime)
        assertEquals(expectedUri, deliveredUri)
    }

    @Test
    fun filePickerLaunchesGetContentVideoForLocalFfmpegExtraction() {
        val expectedUri = Uri.parse("content://media/external/video/media/99")
        var capturedInputMime: String? = null
        var deliveredUri: Uri? = null

        val fakeRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                capturedInputMime = input as? String
                dispatchResult(requestCode, expectedUri)
            }
        }
        val fakeOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = fakeRegistry
        }

        composeTestRule.setContent {
            ToneForgeTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner) {
                    FilePicker(
                        onFileSelected = { uri -> deliveredUri = uri }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pick_video_audio_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_video_audio_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("video/*", capturedInputMime)
        assertEquals(expectedUri, deliveredUri)
    }

    @Test
    fun filePickerDisplaysSelectedMetadataAndLoadingState() {
        composeTestRule.setContent {
            ToneForgeTheme {
                FilePicker(
                    onFileSelected = {},
                    selectedFileName = "strat_crunch_riff.wav",
                    selectedFileSizeBytes = 2_097_152L,
                    isLoading = true
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("file_picker_selected_badge").assertIsDisplayed()
        composeTestRule.onNodeWithText("strat_crunch_riff.wav").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.00 MB · Listo para análisis DSP").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_audio_button").assertIsNotEnabled()
        assertEquals("2.00 MB", formatFileSizeLabel(2_097_152L))
    }
}
