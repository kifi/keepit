package com.keepit.serializer

import com.keepit.search.PersonalSearchHit

import play.api.libs.json._

class PersonalSearchHitSerializer extends Writes[PersonalSearchHit] {
  def writes(hit: PersonalSearchHit): JsValue =
    Json.obj(
      "title" -> hit.title,
      "url" -> hit.url)
}

object PersonalSearchHitSerializer {
  implicit val hitSerializer = new PersonalSearchHitSerializer
}
