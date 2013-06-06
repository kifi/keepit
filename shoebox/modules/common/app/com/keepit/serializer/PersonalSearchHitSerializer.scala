package com.keepit.serializer

import com.keepit.controllers.ext.PersonalSearchHit

import play.api.libs.json._

class PersonalSearchHitSerializer extends Writes[PersonalSearchHit] {
  def writes(hit: PersonalSearchHit): JsValue =
    Json.obj(
      "id" -> hit.externalId.id,
      "title" -> hit.title,
      "url" -> hit.url)
}

object PersonalSearchHitSerializer {
  implicit val hitSerializer = new PersonalSearchHitSerializer
}
