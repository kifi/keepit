package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers.ext.PersonalSearchHit
import com.keepit.model._
import play.api.libs.json._

class PersonalSearchHitSerializer extends Writes[PersonalSearchHit] {

  def writes(hit: PersonalSearchHit): JsValue =
    JsObject(List(
      "externalId"  -> JsString(hit.externalId.toString),
      "title"  -> (hit.title map {t => JsString(t) } getOrElse JsNull),
      "url"  -> JsString(hit.url)
    ))

  def writes (hits: List[PersonalSearchHit]): JsValue =
    JsArray(hits map { hit =>
      JsObject(List(
        "normalizedUriId" -> JsNumber(hit.id.id),
        "normalizedUriObject" -> PersonalSearchHitSerializer.hitSerializer.writes(hit)
      ))
    })
}

object PersonalSearchHitSerializer {
  implicit val hitSerializer = new PersonalSearchHitSerializer
}
