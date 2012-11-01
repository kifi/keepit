package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers._
import play.api.libs.json._

class PersonalSearchResultPacketSerializer extends Writes[PersonalSearchResultPacket] with Logging {
  def writes(res: PersonalSearchResultPacket): JsValue =
    try {
      JsObject(List(
        "uuid" -> JsString(res.uuid.toString),
        "query" -> JsString(res.query),
        "hits" -> URIPersonalSearchResultSerializer.resSerializer.writes(res.hits),
        "mayHaveMore" -> JsBoolean(res.mayHaveMoreHits),
        "context" -> JsString(res.context)
      ))
    } catch {
      case e =>
        log.error("can't serialize %s".format(res))
        throw e
    }
}

object PersonalSearchResultPacketSerializer {
  implicit val resSerializer = new PersonalSearchResultPacketSerializer
}
