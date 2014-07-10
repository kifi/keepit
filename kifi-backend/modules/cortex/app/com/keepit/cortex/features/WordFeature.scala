package com.keepit.cortex.features

import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.cortex.core.StatModel

trait WordRepresenter[M <: StatModel, +FT <: FeatureRepresentation[String, M]] extends FeatureRepresenter[String, M, FT]

abstract class HashMapWordRepresenter[M <: StatModel](
    val dimension: Int,
    val mapper: Map[String, Array[Float]]) extends WordRepresenter[M, FloatVecFeature[String, M]] {

  override def apply(word: String): Option[FloatVecFeature[String, M]] = mapper.get(word).map { FloatVecFeature[String, M](_) }

  override def getRawVector(word: String): Option[Array[Float]] = mapper.get(word)
}
