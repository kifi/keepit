package com.keepit.serializer

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.json._
import org.joda.time.DateTime

class NormalizedURISerializer extends Format[NormalizedURI] {

  def writes(uri: NormalizedURI): JsValue =
    JsObject(List(
      "id"  -> uri.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> Json.toJson(uri.createdAt),
      "updatedAt" -> Json.toJson(uri.updatedAt),
      "externalId" -> JsString(uri.externalId.id),
      "title"  -> (uri.title map { title => JsString(title) } getOrElse(JsNull)),
      "url"  -> JsString(uri.url),
      "urlHash"  -> JsString(uri.urlHash),
      "state"  -> JsString(uri.state.value),
      "seq" -> JsNumber(uri.seq.value)
    ))

  def reads(json: JsValue): JsResult[NormalizedURI] =
    JsSuccess(NormalizedURI(
      id = (json \ "id").asOpt[Long].map(Id[NormalizedURI](_)),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      externalId = ExternalId[NormalizedURI]((json \ "externalId").as[String]),
      title = (json \ "title").asOpt[String],
      url = (json \ "url").as[String],
      urlHash = (json \ "urlHash").as[String],
      state = State[NormalizedURI]((json \ "state").as[String]),
      seq = SequenceNumber((json \ "seq").as[Long])
    ))
}

object NormalizedURISerializer {
  implicit val normalizedURISerializer = new NormalizedURISerializer
}
