package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.json._

class NormalizedURISerializer extends Writes[NormalizedURI] {

  def writes(normalizedURI: NormalizedURI): JsValue =
    JsObject(List(
      "externalId"  -> JsString(normalizedURI.externalId.toString),
      "title"  -> (normalizedURI.title map {t => JsString(t) } getOrElse JsNull),
      "url"  -> JsString(normalizedURI.url)
    ))

  def writes (normalizedURIs: List[NormalizedURI]): JsValue =
    JsArray(normalizedURIs map { normalizedURI =>
      JsObject(List(
        "normalizedUriId" -> JsNumber(normalizedURI.id.get.id),
        "normalizedUriObject" -> NormalizedURISerializer.normalizedURISerializer.writes(normalizedURI)
      ))
    })

}

object NormalizedURISerializer {
  implicit val normalizedURISerializer = new NormalizedURISerializer
}
