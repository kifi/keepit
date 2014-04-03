package com.keepit.cortex.features

import com.keepit.cortex.core.FeatureRepresentation
import com.keepit.cortex.core.FeatureRepresenter
import com.keepit.cortex.core.FloatVecFeature
import com.keepit.cortex.core.StatModel

trait WordRepresenter[M <: StatModel] extends FeatureRepresenter[String, M]

abstract class HashMapWordRepresenter[M <: StatModel](
  mapper: Map[String, Array[Float]]
) extends WordRepresenter[M]{

  override def apply(word: String): Option[FeatureRepresentation[String, M]] = mapper.get(word).map{ FloatVecFeature[String, M](_) }

  override def getRawVector(word: String): Option[Array[Float]] = mapper.get(word)
}
