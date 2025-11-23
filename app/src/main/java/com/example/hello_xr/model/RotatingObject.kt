package com.example.hello_xr.model

import android.annotation.SuppressLint
import android.content.Context
import androidx.xr.runtime.Session
import androidx.xr.scenecore.GltfModel
import com.example.hello_xr.R
import java.io.InputStream

enum class ModelType {
  STAR,
  // RING
}

object RotatingObjectGltfModelCache {
  private var cachedModel: GltfModel? = null
  private var selected_type: ModelType? = null

  @SuppressLint("RestrictedApi")
  suspend fun getOrLoadModel(
    xrCoreSession: Session,
    context: Context,
    request: ModelType
  ): GltfModel? {
    return if (cachedModel == null) {
      val res_id =
        when (request) {
          ModelType.STAR -> R.raw.rounded_star
        // ModelType.RING -> R.raw.ring

        }

      val inputStream: InputStream = context.resources.openRawResource(res_id)
      cachedModel =
        GltfModel.create(xrCoreSession, inputStream.readBytes(), assetKey = "ROUNDED_STAR")
      selected_type = request
      cachedModel
    } else {
      cachedModel
    }
  }

  fun clearCache() {
    cachedModel = null
  }

  const val TAG = "RotatingObjectGltfModelCache"
}
