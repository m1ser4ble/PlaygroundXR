package com.example.hello_xr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.fillMaxSize
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.scale
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.scene
import com.example.hello_xr.HandLandmarkerHelper
import com.example.hello_xr.R
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.io.InputStream
import java.util.concurrent.Executors

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
    xrSession.scene.requestFullSpaceMode()
    // requestPassthrough
    xrSession.scene.spatialEnvironment.preferredPassthroughOpacity = 1f
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
      gltfModel = RoundedStarGltfModelCache.getOrLoadModel(xrSession, context)
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
          animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing))
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
            gltfEntity = entity//.apply { setEnabled(false)  }
          }
        },
        modifier = modifier.scale(0.2f)
      )
    }
  }
  // Clean up the cache when the composable leaves the composition.
  DisposableEffect(Unit) { onDispose { RoundedStarGltfModelCache.clearCache() } }
}

private object RoundedStarGltfModelCache {
  private var cachedModel: GltfModel? = null

  @SuppressLint("RestrictedApi")
  suspend fun getOrLoadModel(xrCoreSession: Session, context: Context): GltfModel? {
    return if (cachedModel == null) {
      val inputStream: InputStream = context.resources.openRawResource(R.raw.rounded_star)
      cachedModel =
        GltfModel.create(xrCoreSession, inputStream.readBytes(), assetKey = "ROUNDED_STAR")
      cachedModel
    } else {
      cachedModel
    }
  }

  fun clearCache() {
    cachedModel = null
  }

  const val TAG = "RoundedStarGltfModelCache"
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun FloatingObjects() {
  var handPose by remember { mutableStateOf<Pose?>(null) }

  Subspace {
    HandTrackingContent() { newPose -> handPose = newPose }
    RoundedStarModel(
      getPose = { handPose },
      modifier = SubspaceModifier.fillMaxSize().offset(z = 400.dp)
    )
  }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun HandTrackingContent(onPoseUpdated: (Pose?) -> Unit) {
  val session = LocalSession.current
  if (session == null) return

  var hasPermission by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
      onResult = { granted -> hasPermission = granted }
    )

  LaunchedEffect(Unit) { permissionLauncher.launch(Manifest.permission.CAMERA) }

  if (hasPermission) {
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
            imageAnalysis
          )
        } catch (e: Exception) {
          Log.e("HandTrackingContent", "Use case binding failed", e)
        }
        onDispose { provider.unbindAll() }
      }

      LaunchedEffect(handLandmarkerHelper) {
        handLandmarkerHelper.resultBundle.collect { resultBundle ->
          if (
            resultBundle != null &&
              resultBundle.results.isNotEmpty() &&
              resultBundle.results.first().landmarks().isNotEmpty()
          ) {
            val hand = resultBundle.results.first().landmarks().first()
            val centerLandmark = hand[9] // MIDDLE_FINGER_MCP

            // Reverting to placeholder logic due to API limitations
            val x = (centerLandmark.x() - 0.5f) * 2f
            val y = (centerLandmark.y() - 0.5f) * -2f
            val z = (centerLandmark.z() * -1f) - 0.5f
            val pose = Pose(Vector3(x, y, z), Quaternion())
            onPoseUpdated(pose)
          } else {
            onPoseUpdated(null)
          }
        }
      }
    }
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

@Composable
fun HelloAndroidXRApp() {
  LogCapabilities()
  EnvironmentControls()
  FloatingObjects()
  // CreateMediaPlayer()

}

  /** Layout that displays content in [SpatialPanel]s, should be used when spatial UI is enabled. */
  /*
  @Composable
  private fun SpatialLayout(
      primaryContent: @Composable () -> Unit,
      firstSupportingContent: @Composable () -> Unit,
      secondSupportingContent: @Composable () -> Unit
  ) {
      val animatedAlpha = remember { Animatable(0.5f) }
      LaunchedEffect(Unit) {
          launch {
              animatedAlpha.animateTo(
                  1.0f,
                  animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
              )
          }
      }
      Subspace {
          SpatialRow(modifier = SubspaceModifier.height(816.dp).fillMaxWidth()) {
              SpatialColumn(modifier = SubspaceModifier.width(400.dp)) {
                  SpatialPanel(
                      SubspaceModifier
                          .alpha(animatedAlpha.value)
                          .size(400.dp)
                          .padding(bottom = 16.dp)
                          .movable()
                          .resizable()
                  ) {
                      firstSupportingContent()
                  }
                  SpatialPanel(
                      SubspaceModifier
                          .alpha(animatedAlpha.value)
                          .weight(1f)
                          .movable()
                          .resizable()
                  ) {
                      secondSupportingContent()
                  }
              }
              SpatialPanel(
                  modifier = SubspaceModifier
                      .alpha(animatedAlpha.value)
                      .fillMaxSize()
                      .padding(left = 16.dp)
                      .movable()
                      .resizable()
              ) {
                  Column {
                      TopAppBar()
                      primaryContent()
                  }
              }
          }
      }
  }
  */
