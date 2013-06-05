package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.Unscrapable
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial
import com.keepit.common.db._
import org.joda.time.DateTime

class UnscrapableSerializer extends Format[Unscrapable] {

  def writes(unscrapable: Unscrapable): JsValue =
    JsObject(List(
      "id"  -> unscrapable.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> Json.toJson(unscrapable.createdAt),
      "updatedAt" -> Json.toJson(unscrapable.updatedAt),
      "pattern" -> JsString(unscrapable.pattern),
      "state"  -> JsString(unscrapable.state.value)
    )
    )

  def reads(json: JsValue): JsResult[Unscrapable] =
    JsSuccess(Unscrapable(
      id = (json \ "id").asOpt[Long].map(Id[Unscrapable](_)),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      pattern = (json \ "pattern").as[String],
      state = State[Unscrapable]((json \ "state").as[String])
    ))
}

object UnscrapableSerializer {
  implicit val unscrapableSerializer = new UnscrapableSerializer
}
