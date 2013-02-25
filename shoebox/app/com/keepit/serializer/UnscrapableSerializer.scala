package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Unscrapable
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial
import com.keepit.common.db._

class UnscrapableSerializer extends Format[Unscrapable] {

  def writes(unscrapable: Unscrapable): JsValue =
    JsObject(List(
      "id"  -> unscrapable.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> JsString(unscrapable.createdAt.toStandardTimeString),
      "updatedAt" -> JsString(unscrapable.updatedAt.toStandardTimeString),
      "pattern" -> JsString(unscrapable.pattern),
      "state"  -> JsString(unscrapable.state.value)
    )
    )

  def writesSeq(unscrapables: Seq[Unscrapable]): JsValue =
    JsArray(unscrapables.map(writes))

  def reads(json: JsValue): JsResult[Unscrapable] =
    JsSuccess(Unscrapable(
      id = (json \ "id").asOpt[Long].map(Id[Unscrapable](_)),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      updatedAt = parseStandardTime((json \ "updatedAt").as[String]),
      pattern = (json \ "pattern").as[String],
      state = State[Unscrapable]((json \ "state").as[String])
    ))

  def readsSeq(json: JsValue) =
    json.as[List[JsObject]].map(reads).map(_.get)
}

object UnscrapableSerializer {
  implicit val unscrapableSerializer = new UnscrapableSerializer
}
