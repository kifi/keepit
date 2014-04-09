package com.keepit.common.queue.messages

import com.keepit.serializer.TypeCode
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI

trait CortexFeatureMessage

abstract class FloatVecFeatureMessage[T](
  id: Id[T],
  seq: SequenceNumber[T],
  modelName: String,
  modelVersion: Int,
  feature: Array[Float]
) extends CortexFeatureMessage

case class DenseLDAURIFeatureMessage(
  id: Id[NormalizedURI],
  seq: SequenceNumber[NormalizedURI],
  modelName: String,
  modelVersion: Int,
  feature: Array[Float]
) extends FloatVecFeatureMessage[NormalizedURI](id, seq, modelName, modelVersion, feature)
