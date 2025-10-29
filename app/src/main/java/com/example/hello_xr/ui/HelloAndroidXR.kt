package com.example.hello_xr.ui

import com.example.hello_xr.R
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration

import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SceneCoreEntity
import androidx.xr.compose.subspace.SceneCoreEntitySizeAdapter
import androidx.xr.compose.subspace.SpatialColumn
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.alpha
import androidx.xr.compose.subspace.layout.fillMaxSize
import androidx.xr.compose.subspace.layout.fillMaxWidth
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.padding
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.scale
import androidx.xr.compose.subspace.layout.size
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.Meter
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.SoundFieldAttributes
import androidx.xr.scenecore.SpatialCapabilities
import androidx.xr.scenecore.SpatialMediaPlayer
import androidx.xr.scenecore.SpatializerConstants
import androidx.xr.scenecore.scene

import java.io.InputStream


@Composable
fun LogCapabilities() {
    val capabilities = LocalSpatialCapabilities.current

    Log.d("EnsureFullspaceMode", "isSpatialUiEnabled: ${capabilities.isSpatialUiEnabled}")
    Log.d("EnsureFullspaceMode", "isContent3dEnabled: ${capabilities.isContent3dEnabled}")
    Log.d("EnsureFullspaceMode", "isSpatialAudioEnabled: ${capabilities.isSpatialAudioEnabled}")
    Log.d("EnsureFullspaceMode", "isPassthroughControlEnabled: ${capabilities.isPassthroughControlEnabled}")
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


private const val bugdroidHeight = 2.08f
// The desired amount of the available layout height to use for the bugdroid
private const val fillRatio = 0.5f

@Composable
fun RoundedStarModel(showBugdroid: Boolean, modifier: SubspaceModifier = SubspaceModifier) {
    if (showBugdroid) {
        val xrSession = checkNotNull(LocalSession.current)
        // Load the GltfModel data before creating the entity.
        var gltfModel by remember { mutableStateOf<GltfModel?>(null) }
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            if (gltfModel == null) {
                gltfModel = RoundedStarGltfModelCache.getOrLoadModel(xrSession, context)
            }
        }

        gltfModel?.let { gltfModel ->
            Subspace {
                var gltf_entity by remember { mutableStateOf<Entity?>(null) }

                val density = LocalDensity.current
                val infiniteTransition = rememberInfiniteTransition(label = "RotationStar")


                val angle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing))
                )
                val offset by infiniteTransition.animateFloat(
                    initialValue = -1f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 12000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                LaunchedEffect(Unit) {
                    snapshotFlow { offset to angle }.collect { (currentOffset, currentAngle) ->
                        gltf_entity?.let { entity ->
                            val rotation = Quaternion.fromAxisAngle(Vector3.Up, currentAngle)
                            val translation = Vector3(currentOffset, currentOffset, currentOffset)
                            entity.setPose(Pose(translation, rotation))

                        }
                    }
                }




                var scale by remember { mutableFloatStateOf(1f) }
                SceneCoreEntity(
                    factory = {
                        GltfModelEntity.create(xrSession, gltfModel).also { entity ->
                            gltf_entity = entity

                        }

                    },
                    sizeAdapter = SceneCoreEntitySizeAdapter(onLayoutSizeChanged = { size ->
                        // Calculate the scale we should use for the entity based on the size the
                        // layout is setting on the SceneCoreEntity
                        val scaleToFillLayoutHeight = Meter
                            .fromPixel(size.height.toFloat(), density).toM() / bugdroidHeight
                        //Limit the scale to a ratio of the available space
                        scale = scaleToFillLayoutHeight * fillRatio
                    }),
                    modifier = modifier.scale(scale)
                )

            }

        }
    }
    // Clean up the cache when the composable leaves the composition.
    DisposableEffect(Unit) {
        onDispose {
            RoundedStarGltfModelCache.clearCache()
        }
    }
}

private object RoundedStarGltfModelCache {
    private var cachedModel: GltfModel? =null

    @SuppressLint("RestrictedApi")
    suspend fun getOrLoadModel(
        xrCoreSession: Session, context: Context
    ): GltfModel? {
        return if (cachedModel == null) {
            val inputStream: InputStream =
                context.resources.openRawResource(R.raw.rounded_star)
            cachedModel =  GltfModel.create(xrCoreSession, inputStream.readBytes(), assetKey = "ROUNDED_STAR")
            cachedModel


        }
        else{
            cachedModel
        }
    }

    fun clearCache() {
        cachedModel = null
    }

    const val TAG = "RoundedStarGltfModelCache"
}

@Composable
fun FloatingObjects() {
    var showBugdroid by rememberSaveable { mutableStateOf(true) }

    RoundedStarModel(
        showBugdroid=showBugdroid,
        modifier = SubspaceModifier
            .fillMaxSize()
            .offset(z = 400.dp)

    )

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

    //return

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
    }
}*/

@Composable
fun HelloAndroidXRApp() {
    LogCapabilities()
    EnvironmentControls()

    FloatingObjects()
    //CreateMediaPlayer()
    /*
    if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
        SpatialLayout(
            primaryContent = { PrimaryContent() },
            firstSupportingContent = { BlockOfContentOne() },
            secondSupportingContent = { BlockOfContentTwo() }
        )
    } else {
        NonSpatialTwoPaneLayout(
            secondaryPane = {
                BlockOfContentOne()
                BlockOfContentTwo()
            },
            primaryPane = { PrimaryContent() }
        )
    }*/
}


/**
 * Layout that displays content in [SpatialPanel]s, should be used when spatial UI is enabled.
 */
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