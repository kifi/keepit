package com.keepit.serializer

import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.controllers.ext._
import play.api.libs.json._

class PersonalSearchResultPacketSerializer extends Writes[PersonalSearchResultPacket] with Logging {
  def writes(res: PersonalSearchResultPacket): JsValue =
    try {
      JsObject(List(
        "uuid" -> JsString(res.uuid.toString),
        "query" -> JsString(res.query),
        "hits" -> PersonalSearchResultSerializer.resSerializer.writes(res.hits),
        "mayHaveMore" -> JsBoolean(res.mayHaveMoreHits),
        "show" -> JsBoolean(res.show),
        "experimentId" -> res.experimentId.map(id => JsNumber(id.id)).getOrElse(JsNull),
        "context" -> JsString(res.context)
      ))
    } catch {
      case e: Throwable =>
        log.error("can't serialize %s".format(res))
        throw e
    }
}

object PersonalSearchResultPacketSerializer {
  implicit val resSerializer = new PersonalSearchResultPacketSerializer
}
