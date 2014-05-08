package com.keepit.cortex.models.lda

import com.keepit.cortex.core.StatModel
import play.api.libs.json._

trait LDA extends StatModel

// mapper: word -> topic vector
case class DenseLDA(dimension: Int, mapper: Map[String, Array[Float]]) extends LDA

case class SparseTopicRepresentation(
  dimension: Int,
  topics: Map[Int, Float]
)

object SparseTopicRepresentation {
  implicit val format: Format[SparseTopicRepresentation] = new Format[SparseTopicRepresentation] {
    def writes(rep: SparseTopicRepresentation): JsValue = {
      Json.obj("dimension" -> rep.dimension, "topicIds" -> rep.topics.map{_._1}, "topicScores" -> rep.topics.map{_._2})
    }

    def reads(json: JsValue): JsResult[SparseTopicRepresentation] = {
      val d = (json \ "dimension").as[Int]
      val ids = (json \ "topicIds").as[JsArray].value.map{_.as[Int]}
      val scores = (json \ "topicScores").as[JsArray].value.map{_.as[Float]}
      val topics = (ids zip scores).toMap
      JsSuccess(SparseTopicRepresentation(d, topics))
    }
  }
}
