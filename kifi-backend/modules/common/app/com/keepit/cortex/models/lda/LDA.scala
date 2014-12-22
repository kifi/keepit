package com.keepit.cortex.models.lda

import com.keepit.cortex.core.StatModel
import play.api.libs.json._
import com.keepit.model.NormalizedURI
import com.keepit.common.db.{ SequenceNumber, Id }
import play.api.libs.functional.syntax._
import com.keepit.cortex.core.Versionable
import com.kifi.macros.json

trait LDA extends StatModel

// mapper: word -> topic vector
case class DenseLDA(dimension: Int, mapper: Map[String, Array[Float]]) extends LDA
case class LDATopic(index: Int) extends AnyVal
object LDATopic {
  implicit val format = Json.format[LDATopic]
}

case class LDATopicFeature(value: Array[Float])

case class SparseTopicRepresentation(
  dimension: Int,
  topics: Map[LDATopic, Float])

object SparseTopicRepresentation {
  implicit val format: Format[SparseTopicRepresentation] = new Format[SparseTopicRepresentation] {
    def writes(rep: SparseTopicRepresentation): JsValue = {
      Json.obj("dimension" -> rep.dimension, "topicIds" -> rep.topics.map { _._1 }, "topicScores" -> rep.topics.map { _._2 })
    }

    def reads(json: JsValue): JsResult[SparseTopicRepresentation] = {
      val d = (json \ "dimension").as[Int]
      val ids = (json \ "topicIds").as[JsArray].value.map { _.as[LDATopic] }
      val scores = (json \ "topicScores").as[JsArray].value.map { _.as[Float] }
      val topics = (ids zip scores).toMap
      JsSuccess(SparseTopicRepresentation(d, topics))
    }
  }
}

case class UriSparseLDAFeatures(uriId: Id[NormalizedURI], uriSeq: SequenceNumber[NormalizedURI], features: SparseTopicRepresentation)
object UriSparseLDAFeatures {
  implicit val format = Json.format[UriSparseLDAFeatures]
}

// editable from admin
case class LDATopicConfiguration(topicName: String, isActive: Boolean, isNameable: Boolean)

object LDATopicConfiguration {
  implicit val format = Json.format[LDATopicConfiguration]
  def default = LDATopicConfiguration("n/a", true, true)
}

case class LDATopicConfigurations(configs: Map[String, LDATopicConfiguration]) extends Versionable[DenseLDA]

object LDATopicConfigurations {
  implicit val format = Json.format[LDATopicConfigurations]
}

case class LDATopicInfo(
  topicId: Int,
  pmiScore: Option[Float],
  topicWords: Map[String, Float],
  config: LDATopicConfiguration)

object LDATopicInfo {
  implicit val format = (
    (__ \ 'topicId).format[Int] and
    (__ \ 'pmiScore).format[Option[Float]] and
    (__ \ 'topicWords).format[Map[String, Float]] and
    (__ \ 'config).format[LDATopicConfiguration]
  )(LDATopicInfo.apply, unlift(LDATopicInfo.unapply))
}

case class LDATopicDetail(
  topicId: Int,
  sampleURIs: Seq[NormalizedURI],
  topicScores: Seq[Float])

@json case class LDAUserURIInterestScore(score: Float, confidence: Float) // confidence: [0,1]. higher better
@json case class LDAUserURIInterestScores(
  global: Option[LDAUserURIInterestScore],
  recency: Option[LDAUserURIInterestScore],
  libraryInduced: Option[LDAUserURIInterestScore],
  topic1: Option[LDATopic] = None,
  topic2: Option[LDATopic] = None)
