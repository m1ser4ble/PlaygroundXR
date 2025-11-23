package com.example.hello_xr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.xr.arcore.Hand
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialExternalSurface
import androidx.xr.compose.subspace.StereoMode
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.fillMaxSize
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.scale
import androidx.xr.compose.subspace.layout.width
import androidx.xr.runtime.Config
import androidx.xr.runtime.FieldOfView
import androidx.xr.runtime.HandJointType
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.scene
import com.example.hello_xr.R
import com.example.hello_xr.model.ModelType
import com.example.hello_xr.model.RotatingObjectGltfModelCache
import com.example.hello_xr.util.HandLandmarkerHelper
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark
import java.util.concurrent.Executors
import kotlin.collections.get
import kotlin.div
import kotlin.math.absoluteValue
import kotlin.text.toFloat
import kotlin.times
import kotlinx.coroutines.flow.collect

@Composable
fun LogCapabilities() {
  val capabilities = LocalSpatialCapabilities.current

  Log.d("EnsureFullspaceMode", "isSpatialUiEnabled: ${capabilities.isSpatialUiEnabled}")
  Log.d("EnsureFullspaceMode", "isContent3dEnabled: ${capabilities.isContent3dEnabled}")
  Log.d("EnsureFullspaceMode", "isSpatialAudioEnabled: ${capabilities.isSpatialAudioEnabled}")
  Log.d(
    "EnsureFullspaceMode",
    "isPassthroughControlEnabled: ${capabilities.isPassthroughControlEnabled}"
  )
  Log.d("EnsureFullspaceMode", "isAppEnvironmentEnabled: ${capabilities.isAppEnvironmentEnabled}")
}

@Composable
fun EnvironmentControls(modifier: Modifier = Modifier) {
  val activity = LocalActivity.current
  val session = LocalSession.current

  if (session != null && activity is ComponentActivity) {
    val uiIsSpatialized = LocalSpatialCapabilities.current.isSpatialUiEnabled

    val xrSession = session
    // fullspace mode
    LocalSpatialConfiguration.current.requestFullSpaceMode()

    // xrSession.scene.requestFullSpaceMode()

    // requestPassthrough
    xrSession.scene.spatialEnvironment.preferredPassthroughOpacity = 1f
  }
}

@SuppressLint("RestrictedApi")
@Composable
fun UserHandTracking(onPoseUpdated: (Pose?) -> Unit) {
  val session = LocalSession.current
  if (session == null) return

  var hasPermission by remember { mutableStateOf(false) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
      onResult = { granted -> hasPermission = granted }
    )
  // Composable이 처음 시작될 때 권한을 요청합니다.
  LaunchedEffect(Unit) {
    val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
    permissionLauncher.launch(HAND_TRACKING_PERMISSION)
  }

  // 💡 권한이 부여되었을 때만 세션을 설정하도록 변경합니다.
  if (hasPermission) {
    // configure handtracking enabled
    val newConfig = session.config.copy(handTracking = Config.HandTrackingMode.BOTH)
    when (val result = session.configure(newConfig)) {
      is SessionConfigureSuccess -> Log.d("tag", "Hand tracking configured successfully")
      else -> Log.d("tag", "Hand tracking configuration failed")
    }

    LaunchedEffect(Unit) {
      Hand.left(session)?.state?.collect { handState -> // or Hand.right(session)
        val palmPose = handState.handJoints[HandJointType.PALM]
        if (palmPose == null) return@collect
        val transformedPose =
          session.scene.perceptionSpace.transformPoseTo(
            palmPose,
            session.scene.activitySpace,
          )
        onPoseUpdated(transformedPose)
        Log.d("UserHandTracking", "$palmPose -> $transformedPose")

        // Hand state has been updated.
        // Use the state of hand joints to update an entity's position.
        // renderPlanetAtHandPalm(handState)

      }
    }
  }
}

@Composable
fun RoundedStarModel(getPose: () -> Pose?, modifier: SubspaceModifier = SubspaceModifier) {
  val xrSession = checkNotNull(LocalSession.current)
  // Load the GltfModel data before creating the entity.
  var gltfModel by remember { mutableStateOf<GltfModel?>(null) }
  val context = LocalContext.current

  LaunchedEffect(Unit) {
    if (gltfModel == null) {
      gltfModel = RotatingObjectGltfModelCache.getOrLoadModel(xrSession, context, ModelType.STAR)
    }
  }

  gltfModel?.let { model ->
    Subspace {
      var gltfEntity by remember { mutableStateOf<Entity?>(null) }

      val infiniteTransition = rememberInfiniteTransition(label = "RotationStar")
      val angle by
        infiniteTransition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing))
        )

      LaunchedEffect(Unit) {
        snapshotFlow { getPose() to angle }
          .collect { (currentPose, currentAngle) ->
            gltfEntity?.let { entity ->
              if (currentPose != null) {
                val rotation = Quaternion.fromAxisAngle(Vector3.Up, currentAngle)
                val translation = currentPose.translation
                entity.setPose(Pose(translation, rotation))
                entity.setEnabled(true)
              } else {
                entity.setEnabled(false)
              }
            }
          }
      }

      SceneCoreEntity(
        factory = {
          GltfModelEntity.create(xrSession, model).also { entity ->
            gltfEntity = entity // .apply { setEnabled(false)  }
          }
        },
        modifier = modifier.scale(0.2f)
      )
    }
  }
  // Clean up the cache when the composable leaves the composition, not recomposition occurring
  DisposableEffect(Unit) { onDispose { RotatingObjectGltfModelCache.clearCache() } }
}

@ExperimentalComposeApi
@OptIn(ExperimentalComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SpatialExternalSurfaceContent() {
  val context = LocalContext.current
  Subspace {
    SpatialExternalSurface(
      modifier =
        SubspaceModifier.width(1200.dp) // Default width is 400.dp if no width modifier is specified
          .height(676.dp), // Default height is 400.dp if no height modifier is specified
      // Use StereoMode.Mono, StereoMode.SideBySide, or StereoMode.TopBottom, depending
      // upon which type of content you are rendering: monoscopic content, side-by-side stereo
      // content, or top-bottom stereo content
      stereoMode = StereoMode.SideBySide,
    ) {
      val exoPlayer = remember { ExoPlayer.Builder(context).build() }
      Log.d("SpatialExternalSurfaceContent", "video uri creating")

      // val videoUri = RawResourceDataSource.buildRawResourceUri(R.raw.sbs_video)
      val videoUri =
        Uri.Builder()
          .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
          // Represents a side-by-side stereo video, where each frame contains a pair of
          // video frames arranged side-by-side. The frame on the left represents the left
          // eye view, and the frame on the right represents the right eye view.
          .path("${R.raw.sbs_video}")
          .build()
      Log.d("SpatialExternalSurfaceContent", "video uri : $videoUri")
      val mediaItem = MediaItem.fromUri(videoUri)

      Log.d("SpatialExternalSurfaceContent", "mediaItem : $mediaItem")

      // onSurfaceCreated is invoked only one time, when the Surface is created
      onSurfaceCreated { surface ->
        exoPlayer.setVideoSurface(surface)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        Log.d("SpatialExternalSurfaceContent", "exoPlayer : $exoPlayer")
        exoPlayer.play()
        Log.d("SpatialExternalSurfaceContent", "play called well... ")
      }
      // onSurfaceDestroyed is invoked when the SpatialExternalSurface composable and its
      // associated Surface are destroyed
      onSurfaceDestroyed { exoPlayer.release() }
    }
  }
}

@SuppressLint("RestrictedApi")
data class CameraInfo(val fx: Double, val fy: Double, val depthMeters: Float)

@SuppressLint("RestrictedApi")
fun GetCameraInfo(
  mpLandmarks: List<NormalizedLandmark>,
  imageWidth: Int,
  imageHeight: Int, // Height 추가 필요
  fov: FieldOfView
): CameraInfo { // Double -> Float 통일
  val wrist = mpLandmarks[HandLandmark.WRIST]
  val middleMcp = mpLandmarks[HandLandmark.MIDDLE_FINGER_MCP]

  // 1. [중요] 정규화 좌표 -> 픽셀 좌표로 변환 (Aspect Ratio 문제 해결)
  val wristX = wrist.x() * imageWidth
  val wristY = wrist.y() * imageHeight
  val middleX = middleMcp.x() * imageWidth
  val middleY = middleMcp.y() * imageHeight

  // 2. 픽셀 단위 거리 측정
  val dx = wristX - middleX
  val dy = wristY - middleY
  val distanceInPixels = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

  Log.d("calculateDepthFromPalSize", "distance in pixels ${distanceInPixels}")
  // 3. 초점 거리(fx) 계산 (픽셀 단위)
  // fov.angleLeft + fov.angleRight 는 수평 FOV
  val fovH = fov.angleLeft.absoluteValue + fov.angleRight.absoluteValue
  val fovHRads = fovH.toDouble()

  val fovScale = 2.0f // <- 이 값을 0.8, 0.9, 1.1, 1.2 등으로 바꿔보세요!

  val fovVRads = fov.angleUp.absoluteValue + fov.angleDown.absoluteValue
  val fy = (imageHeight / 2.0) / Math.tan(fovVRads / 2.0)
  val fx = (imageWidth / 2.0) / Math.tan(fovHRads / 2.0)
  Log.d("calculateDepthFromPalSize", "fx ${fx}.. , ${fov.angleLeft}, ${fov.angleRight}")

  // 4. 깊이(Z) 역산
  // 기준: 성인 손목~중지뿌리 거리 약 8~9cm (0.09m)
  val realHandSizeMeters = 0.09f

  // Z = (f * real_size) / pixel_size
  val depthMeters = (fx * realHandSizeMeters) / distanceInPixels
  Log.d("calculateDepthFromPalSize", "DepthMeters ${depthMeters}")

  return CameraInfo(fx = fx * fovScale, fy = fy * fovScale, depthMeters.toFloat())
}

@SuppressLint("RestrictedApi")
private fun calculatePalmPositionInCameraSpace(
  handLandmarks: List<NormalizedLandmark>,
  inputWidth: Int,
  inputHeight: Int,
  fov: FieldOfView
): Vector3 {
  val centerLandmark = handLandmarks[HandLandmark.MIDDLE_FINGER_MCP]

  // 외부 함수 GetCameraInfo 호출 (Naming Convention 수정: snake_case -> camelCase)
  val cameraInfo = GetCameraInfo(handLandmarks, inputWidth, inputHeight, fov)

  // 좌표 변환 로직
  // x: (norm - 0.5) * Width -> 중앙 기준 픽셀 좌표
  // y: (0.5 - norm) * Height -> Y축 반전 및 중앙 기준
  val pixelX = (centerLandmark.x() - 0.5f) * inputWidth
  val pixelY = (0.5f - centerLandmark.y()) * inputHeight

  // 역투영 공식 ($X = u * Z / f_x$)
  val metricX = (pixelX * cameraInfo.depthMeters) / cameraInfo.fx
  val metricY = (pixelY * cameraInfo.depthMeters) / cameraInfo.fy
  val metricZ = -cameraInfo.depthMeters // 카메라는 -Z 방향을 바라봄

  return Vector3(metricX.toFloat(), metricY.toFloat(), metricZ.toFloat())
}

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalGetImage::class)
@Composable
fun HandTrackingContent(onPoseUpdated: (Pose?) -> Unit) {
  val session = LocalSession.current ?: return

  var hasPermission by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
      onResult = { granted -> hasPermission = granted }
    )

  LaunchedEffect(Unit) {
    val head_tracking_permission = "android.permission.HEAD_TRACKING"
    permissionLauncher.launch(Manifest.permission.CAMERA)
    permissionLauncher.launch(head_tracking_permission)
  }

  if (hasPermission) {
    /* on success for getting permission */
    val newConfig =
      session.config.copy(
        headTracking = Config.HeadTrackingMode.LAST_KNOWN,
      )
    val result = session.configure(newConfig)

    /*request for camera provider */
    var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
    LaunchedEffect(context) {
      ProcessCameraProvider.getInstance(context).also { future ->
        future.addListener(
          { cameraProvider = future.get() },
          ContextCompat.getMainExecutor(context)
        )
      }
    }

    cameraProvider?.let { provider ->
      val executor = remember { Executors.newSingleThreadExecutor() }
      val handLandmarkerHelper = remember {
        HandLandmarkerHelper(context = context, runningMode = RunningMode.LIVE_STREAM)
      }

      val imageAnalysis = remember {
        ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .build()
          .also {
            it.setAnalyzer(executor) { imageProxy ->
              handLandmarkerHelper.detectLiveStream(imageProxy = imageProxy, isFrontCamera = false)
            }
          }
      }

      DisposableEffect(provider, imageAnalysis) {
        try {
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            imageAnalysis /*UseCase 는 CameraX에서 카메라 기능 단위를 추상화한 객체.*/
          )
        } catch (e: Exception) {
          Log.e("HandTrackingContent", "Use case binding failed", e)
        }
        onDispose { provider.unbindAll() }
      }

      LaunchedEffect(handLandmarkerHelper) {
        handLandmarkerHelper.resultBundle.collect { resultBundle ->
          val validResult = resultBundle?.results?.firstOrNull()
          val handLandmarks = validResult?.landmarks()?.firstOrNull()
          if (resultBundle == null || handLandmarks == null) {
            onPoseUpdated(null)
            return@collect
          }

          val cameraView = session.scene.spatialUser.cameraViews.firstNotNullOfOrNull { it.value }
          if (cameraView == null) return@collect

          val palmPositionInCameraSpace =
            calculatePalmPositionInCameraSpace(
              handLandmarks = handLandmarks,
              inputWidth = resultBundle.inputImageWidth,
              inputHeight = resultBundle.inputImageHeight,
              fov = cameraView.fov
            )

          // 4. 월드 좌표계로 변환
          val cameraPoseInWorld = cameraView.activitySpacePose
          val handInActivitySpace = cameraPoseInWorld.transformPoint(palmPositionInCameraSpace)
          val transformedPose = Pose(handInActivitySpace, Quaternion())

          // 5. 상태 업데이트 및 로깅
          onPoseUpdated(transformedPose)
          Log.d("Handtracking", "PalmPose in ActivitySpace: $transformedPose")
        }
      }
    }
  }
}

@ExperimentalComposeApi
@OptIn(
  ExperimentalGetImage::class,
)
@Composable
fun FloatingObjects() {
  var handPose by remember { mutableStateOf<Pose?>(null) }

  Subspace {
    HandTrackingContent() { newPose -> handPose = newPose }
    RoundedStarModel(
      getPose = { handPose },
      modifier = SubspaceModifier.fillMaxSize().offset(z = 400.dp)
    )
    /*
    UserHandTracking() {
      //newPose -> handPose = newPose
    }*/
    // SpatialExternalSurfaceContent()
  }
}

@Composable
fun CreateMediaPlayer() {
  // Check spatial capabilities before using spatial audio
  val activity = LocalActivity.current
  val session = LocalSession.current

  if (session == null) return
  val context = LocalContext.current
  var mediaPlayer = MediaPlayer.create(context, R.raw.test)
  mediaPlayer.start() // no need to call prepare(); create() does that for you

  // return

  // I dont know why it is not working
  /*if (session.scene.spatialCapabilities.hasCapability(SpatialCapabilities.SPATIAL_CAPABILITY_SPATIAL_AUDIO)) {
      // The session has spatial audio capabilities

      val soundFieldAttributes =
          SoundFieldAttributes(SpatializerConstants.AMBISONICS_ORDER_FIRST_ORDER)
      val mediaPlayer = MediaPlayer()

      //val soundFieldAudio = context.assets.openFd("sounds/test.mp3")

      val soundFieldAudio  = context.resources.openRawResourceFd(R.raw.test)
      mediaPlayer.reset()
      mediaPlayer.setDataSource(soundFieldAudio)


      val audioAttributes =
          AudioAttributes.Builder()
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .build()

      SpatialMediaPlayer.setSoundFieldAttributes(
          session,
          mediaPlayer,
          soundFieldAttributes
      )
      mediaPlayer.setAudioAttributes(audioAttributes)
      mediaPlayer.prepare()
      mediaPlayer.start()
      Log.d("CreateMeidaPlayer", "me called is playing? ${mediaPlayer.isPlaying}")

  } else {
      // The session does not have spatial audio capabilities
  }*/
}

@kotlin.OptIn(ExperimentalComposeApi::class)
@Composable
fun HelloAndroidXRApp() {
  LogCapabilities()
  EnvironmentControls()
  FloatingObjects()

  DebugPane()

  // CreateMediaPlayer()

}
