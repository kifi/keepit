package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model.{URIHistory, NormalizedURIMetadata, URIHistoryCause, NormalizedURI}
import com.keepit.search.Lang
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.common.analytics.reports._

class NormalizedURIMetadataSerializer extends Format[NormalizedURIMetadata] {
  def writes(metadata: NormalizedURIMetadata): JsValue =
    JsObject(List(
      "url" -> JsString(metadata.originalUrl),
      "context" -> JsString(metadata.context),
      "history" -> JsArray(metadata.history map (r => JsObject(
        Seq("date" -> JsString(r.date.toStandardTimeString), "id" -> JsNumber(r.id.id), "cause" -> JsString(r.cause.value))
      )))
    ))

  def reads(json: JsValue): NormalizedURIMetadata = {
    val originalUrl = (json \ "url").as[String]
    val context = (json \ "context").as[String]
    val history = (json \ "history").as[List[JsObject]].map { h =>
      val date = parseStandardTime((h \ "date").as[String])
      val id = Id[NormalizedURI]((h \ "id").as[Int])
      val cause = URIHistoryCause((h \ "cause").as[String])
      URIHistory(date, id, cause)
    }
    NormalizedURIMetadata(originalUrl = originalUrl, context = context, history = history)
  }
}

object NormalizedURIMetadataSerializer {
  implicit val normalizedURIMetadataSerializer = new NormalizedURIMetadataSerializer
}

