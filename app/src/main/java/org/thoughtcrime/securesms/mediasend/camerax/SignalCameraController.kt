/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.camerax

import android.Manifest
import android.content.Context
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.google.common.util.concurrent.ListenableFuture
import org.signal.core.util.ThreadUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.TextSecurePreferences
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * This is a class to manage the camera resource, and relies on the AndroidX CameraX library.
 *
 * The API is a subset of the [CameraController] class, but with a few additions such as [setImageRotation].
 */
class SignalCameraController(val context: Context, val lifecycleOwner: LifecycleOwner, private val previewView: PreviewView) {
  companion object {
    val TAG = Log.tag(SignalCameraController::class.java)

    @JvmStatic
    private fun isLandscape(surfaceRotation: Int): Boolean {
      return surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270
    }
  }

  private val videoQualitySelector: QualitySelector = QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityThan(Quality.HD))
  private val imageMode = CameraXUtil.getOptimalCaptureMode()

  private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(context)
  private val viewPort: ViewPort? = previewView.getViewPort(Surface.ROTATION_0)
  private val initializationCompleteListeners: MutableSet<InitializationListener> = mutableSetOf()
  private val customUseCases: MutableList<UseCase> = mutableListOf()

  private var imageRotation = 0
  private var recording: Recording? = null
  private var previewTargetSize: Size? = null
  private var imageCaptureTargetSize: Size? = null
  private var cameraSelector: CameraSelector = CameraXUtil.toCameraSelector(TextSecurePreferences.getDirectCaptureCameraId(context))
  private var enabledUseCases: Int = CameraController.IMAGE_CAPTURE

  private var previewUseCase: Preview = createPreviewUseCase()
  private var imageCaptureUseCase: ImageCapture = createImageCaptureUseCase()
  private var videoCaptureUseCase: VideoCapture<Recorder> = createVideoCaptureRecorder()

  private lateinit var cameraProvider: ProcessCameraProvider
  private lateinit var camera: Camera

  @RequiresPermission(Manifest.permission.CAMERA)
  fun bindToLifecycle(onCameraBoundListener: Runnable) {
    ThreadUtil.assertMainThread()
    if (this::cameraProvider.isInitialized) {
      bindToLifecycleInternal()
      onCameraBoundListener.run()
    } else {
      cameraProviderFuture.addListener({
        cameraProvider = cameraProviderFuture.get()
        initializationCompleteListeners.forEach { it.onInitialized(cameraProvider) }
        bindToLifecycleInternal()
        onCameraBoundListener.run()
      }, ContextCompat.getMainExecutor(context))
    }
  }

  @MainThread
  fun unbind() {
    ThreadUtil.assertMainThread()
    cameraProvider.unbindAll()
  }

  @MainThread
  private fun bindToLifecycleInternal() {
    ThreadUtil.assertMainThread()
    try {
      if (!this::cameraProvider.isInitialized) {
        Log.d(TAG, "Camera provider not yet initialized.")
        return
      }
      camera = cameraProvider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        buildUseCaseGroup()
      )

      initializeTapToFocus()
    } catch (e: Exception) {
      Log.e(TAG, "Use case binding failed", e)
    }
  }

  @MainThread
  fun addUseCase(useCase: UseCase) {
    ThreadUtil.assertMainThread()

    customUseCases += useCase

    if (isRecording()) {
      stopRecording()
    }

    tryToBindCamera()
  }

  @MainThread
  fun takePicture(executor: Executor, callback: ImageCapture.OnImageCapturedCallback) {
    ThreadUtil.assertMainThread()
    assertImageEnabled()
    imageCaptureUseCase.takePicture(executor, callback)
  }

  @RequiresApi(26)
  @MainThread
  fun startRecording(outputOptions: FileDescriptorOutputOptions, audioConfig: AudioConfig, videoSavedListener: Consumer<VideoRecordEvent>): Recording {
    ThreadUtil.assertMainThread()
    assertVideoEnabled()

    recording?.stop()
    recording = null
    val startedRecording = videoCaptureUseCase.output
      .prepareRecording(context, outputOptions)
      .apply {
        if (audioConfig.audioEnabled) {
          withAudioEnabled()
        }
      }
      .start(ContextCompat.getMainExecutor(context)) {
        videoSavedListener.accept(it)
        if (it is VideoRecordEvent.Finalize) {
          recording = null
        }
      }

    recording = startedRecording
    return startedRecording
  }

  @MainThread
  fun setEnabledUseCases(useCaseFlags: Int) {
    ThreadUtil.assertMainThread()
    if (enabledUseCases == useCaseFlags) {
      return
    }

    val oldEnabledUseCases = enabledUseCases
    enabledUseCases = useCaseFlags
    if (isRecording()) {
      stopRecording()
    }
    tryToBindCamera { enabledUseCases = oldEnabledUseCases }
  }

  @MainThread
  fun getImageCaptureFlashMode(): Int {
    ThreadUtil.assertMainThread()
    return imageCaptureUseCase.flashMode
  }

  @MainThread
  fun setPreviewTargetSize(size: Size) {
    ThreadUtil.assertMainThread()
    if (size == previewTargetSize || previewTargetSize?.equals(size) == true) {
      return
    }
    Log.d(TAG, "Setting Preview dimensions to $size")
    previewTargetSize = size
    if (this::cameraProvider.isInitialized) {
      cameraProvider.unbind(previewUseCase)
    }
    previewUseCase = createPreviewUseCase()

    tryToBindCamera(null)
  }

  @MainThread
  fun setImageCaptureTargetSize(size: Size) {
    ThreadUtil.assertMainThread()
    if (size == imageCaptureTargetSize || imageCaptureTargetSize?.equals(size) == true) {
      return
    }
    imageCaptureTargetSize = size
    if (this::cameraProvider.isInitialized) {
      cameraProvider.unbind(imageCaptureUseCase)
    }
    imageCaptureUseCase = createImageCaptureUseCase()
    tryToBindCamera(null)
  }

  @MainThread
  fun setImageRotation(rotation: Int) {
    ThreadUtil.assertMainThread()
    val newRotation = UseCase.snapToSurfaceRotation(rotation.coerceIn(0, 359))

    if (newRotation == imageRotation) {
      return
    }

    if (isLandscape(newRotation) != isLandscape(imageRotation)) {
      imageCaptureTargetSize = imageCaptureTargetSize?.swap()
    }

    videoCaptureUseCase.targetRotation = newRotation
    imageCaptureUseCase.targetRotation = newRotation

    imageRotation = newRotation
  }

  @MainThread
  fun setImageCaptureFlashMode(flashMode: Int) {
    ThreadUtil.assertMainThread()
    imageCaptureUseCase.flashMode = flashMode
  }

  @MainThread
  fun setZoomRatio(ratio: Float): ListenableFuture<Void> {
    ThreadUtil.assertMainThread()
    return camera.cameraControl.setZoomRatio(ratio)
  }

  @MainThread
  fun getZoomState(): LiveData<ZoomState> {
    ThreadUtil.assertMainThread()
    return camera.cameraInfo.zoomState
  }

  @MainThread
  fun setCameraSelector(selector: CameraSelector) {
    ThreadUtil.assertMainThread()
    if (selector == cameraSelector) {
      return
    }

    val oldCameraSelector: CameraSelector = cameraSelector
    cameraSelector = selector
    if (!this::cameraProvider.isInitialized) {
      return
    }
    cameraProvider.unbindAll()
    tryToBindCamera { cameraSelector = oldCameraSelector }
  }

  @MainThread
  fun getCameraSelector(): CameraSelector {
    ThreadUtil.assertMainThread()
    return cameraSelector
  }

  @MainThread
  fun hasCamera(selectedCamera: CameraSelector): Boolean {
    ThreadUtil.assertMainThread()
    return cameraProvider.hasCamera(selectedCamera)
  }

  @MainThread
  fun addInitializationCompletedListener(listener: InitializationListener) {
    ThreadUtil.assertMainThread()
    initializationCompleteListeners.add(listener)
  }

  @MainThread
  private fun tryToBindCamera(restoreStateRunnable: (() -> Unit)? = null) {
    ThreadUtil.assertMainThread()
    try {
      bindToLifecycleInternal()
    } catch (e: RuntimeException) {
      Log.i(TAG, "Could not re-bind camera!", e)
      restoreStateRunnable?.invoke()
    }
  }

  @MainThread
  private fun stopRecording() {
    ThreadUtil.assertMainThread()
    recording?.close()
  }

  private fun createVideoCaptureRecorder() = VideoCapture.Builder(
    Recorder.Builder()
      .setQualitySelector(videoQualitySelector)
      .build()
  )
    .setTargetRotation(imageRotation)
    .build()

  private fun createPreviewUseCase() = Preview.Builder()
    .apply {
      setTargetRotation(Surface.ROTATION_0)
      val size = previewTargetSize
      if (size != null) {
        setResolutionSelector(
          ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        )
      }
    }.build()
    .also {
      it.setSurfaceProvider(previewView.surfaceProvider)
    }

  private fun createImageCaptureUseCase(): ImageCapture = ImageCapture.Builder()
    .apply {
      setCaptureMode(imageMode)
      setTargetRotation(imageRotation)

      val size = imageCaptureTargetSize
      if (size != null) {
        setResolutionSelector(
          ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        )
      }
    }.build()

  private fun buildUseCaseGroup() = UseCaseGroup.Builder().apply {
    addUseCase(previewUseCase)
    if (isUseCaseEnabled(CameraController.IMAGE_CAPTURE)) {
      addUseCase(imageCaptureUseCase)
    } else {
      cameraProvider.unbind(imageCaptureUseCase)
    }
    if (isUseCaseEnabled(CameraController.VIDEO_CAPTURE)) {
      addUseCase(videoCaptureUseCase)
    } else {
      cameraProvider.unbind(videoCaptureUseCase)
    }

    for (useCase in customUseCases) {
      addUseCase(useCase)
    }

    if (viewPort != null) {
      setViewPort(viewPort)
    } else {
      Log.d(TAG, "ViewPort was null, not adding to UseCase builder.")
    }
  }.build()

  @MainThread
  private fun initializeTapToFocus() {
    ThreadUtil.assertMainThread()
    previewView.setOnTouchListener { v: View?, event: MotionEvent ->
      if (event.action == MotionEvent.ACTION_DOWN) {
        return@setOnTouchListener true
      }
      if (event.action == MotionEvent.ACTION_UP) {
        focusAndMeterOnPoint(event.x, event.y)
        v?.performClick()
        return@setOnTouchListener true
      }
      false
    }
  }

  @MainThread
  private fun focusAndMeterOnPoint(x: Float, y: Float) {
    ThreadUtil.assertMainThread()
    if (this::camera.isInitialized) {
      Log.d(TAG, "Can't tap to focus before camera is initialized.")
      return
    }
    val factory = previewView.meteringPointFactory
    val point = factory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(point).build()

    val future: ListenableFuture<FocusMeteringResult> = camera.cameraControl.startFocusAndMetering(action)
    future.addListener({
      try {
        val result = future.get()
        Log.d(TAG, "Tap to focus was successful? ${result.isFocusSuccessful}")
      } catch (e: ExecutionException) {
        Log.d(TAG, "Tap to focus could not be completed due to an exception.", e)
      } catch (e: InterruptedException) {
        Log.d(TAG, "Tap to focus could not be completed due to an exception.", e)
      }
    }, ContextCompat.getMainExecutor(context))
  }

  private fun isRecording(): Boolean {
    return recording != null
  }

  private fun isUseCaseEnabled(mask: Int): Boolean {
    return (enabledUseCases and mask) != 0
  }

  private fun assertVideoEnabled() {
    if (!isUseCaseEnabled(CameraController.VIDEO_CAPTURE)) {
      throw IllegalStateException("VideoCapture disabled.")
    }
  }

  private fun assertImageEnabled() {
    if (!isUseCaseEnabled(CameraController.IMAGE_CAPTURE)) {
      throw IllegalStateException("ImageCapture disabled.")
    }
  }

  private fun Size.swap(): Size {
    return Size(this.height, this.width)
  }

  interface InitializationListener {
    fun onInitialized(cameraProvider: ProcessCameraProvider)
  }
}
