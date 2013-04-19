package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.controllers.ext.PersonalSearchHit
import play.api.libs.json._

class PersonalSearchHitSerializer extends Writes[PersonalSearchHit] {
  def writes(hit: PersonalSearchHit): JsValue =
    Json.obj(
      "id" -> hit.externalId.id,
      "externalId" -> hit.externalId.id,  // TODO: remove after Apr 30 2013
      "title" -> hit.title,
      "url" -> hit.url)
}

object PersonalSearchHitSerializer {
  implicit val hitSerializer = new PersonalSearchHitSerializer
}
