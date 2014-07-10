package com.keepit.cortex.models.lda

import com.keepit.cortex.core.{ BinaryFormatter, StatModel, Versionable }
import com.keepit.cortex.store.StoreUtil
import play.api.libs.json._

object DenseLDAFormatter extends BinaryFormatter[DenseLDA] {

  def toBinary(lda: DenseLDA): Array[Byte] = StoreUtil.DenseWordVecFormatter.toBinary(lda.dimension, lda.mapper)
  def fromBinary(bytes: Array[Byte]): DenseLDA = {
    val (dim, mapper) = StoreUtil.DenseWordVecFormatter.fromBinary(bytes)
    DenseLDA(dim, mapper)
  }
}

case class DenseLDATopicWords(topicWords: Array[Map[String, Float]]) extends Versionable[DenseLDA]

object DenseLDATopicWordsFormmater extends Format[DenseLDATopicWords] {

  def reads(json: JsValue): JsResult[DenseLDATopicWords] = {
    val x = json.as[Array[Map[String, Float]]]
    JsSuccess(DenseLDATopicWords(x))
  }

  def writes(topicWords: DenseLDATopicWords) = Json.toJson(topicWords.topicWords)
}
