package com.keepit.cortex.models.lda

import com.keepit.cortex.core.StatModel
import play.api.libs.json._
import com.keepit.model.NormalizedURI
import com.keepit.common.db.{SequenceNumber, Id}

trait LDA extends StatModel

// mapper: word -> topic vector
case class DenseLDA(dimension: Int, mapper: Map[String, Array[Float]]) extends LDA
case class LDATopic(index: Int) extends AnyVal
object LDATopic {
  implicit val format = Json.format[LDATopic]
}

case class SparseTopicRepresentation(
  dimension: Int,
  topics: Map[LDATopic, Float]
)

object SparseTopicRepresentation {
  implicit val format: Format[SparseTopicRepresentation] = new Format[SparseTopicRepresentation] {
    def writes(rep: SparseTopicRepresentation): JsValue = {
      Json.obj("dimension" -> rep.dimension, "topicIds" -> rep.topics.map{_._1}, "topicScores" -> rep.topics.map{_._2})
    }

    def reads(json: JsValue): JsResult[SparseTopicRepresentation] = {
      val d = (json \ "dimension").as[Int]
      val ids = (json \ "topicIds").as[JsArray].value.map{_.as[LDATopic]}
      val scores = (json \ "topicScores").as[JsArray].value.map{_.as[Float]}
      val topics = (ids zip scores).toMap
      JsSuccess(SparseTopicRepresentation(d, topics))
    }
  }
}

case class UriSparseLDAFeatures(uriId: Id[NormalizedURI], uriSeq: SequenceNumber[NormalizedURI], features: SparseTopicRepresentation)
object UriSparseLDAFeatures {
  implicit val format = Json.format[UriSparseLDAFeatures]
}
