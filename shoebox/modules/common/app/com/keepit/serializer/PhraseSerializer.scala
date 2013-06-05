package com.keepit.serializer

import com.keepit.common.time._
import com.keepit.model.Phrase
import play.api.libs.json._
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.search.Lang

class PhraseSerializer extends Format[Phrase] {

  def writes(phrase: Phrase): JsValue =
    JsObject(List(
      "id"  -> phrase.id.map(phraseID => JsNumber(phraseID.id)).getOrElse(JsNull),
      "createdAt" -> Json.toJson(phrase.createdAt),
      "updatedAt" -> Json.toJson(phrase.updatedAt),
      "phrase" -> JsString(phrase.phrase),
      "lang"  -> JsString(phrase.lang.lang),
      "source"  -> JsString(phrase.source),
      "state" -> JsString(phrase.state.value)
    ))

  def reads(json: JsValue): JsResult[Phrase] =
    JsSuccess(Phrase(
      id = (json \ "id").asOpt[Long].map(Id[Phrase](_)),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      phrase = (json \ "phrase").as[String],
      lang = Lang((json \ "lang").as[String]),
      source = (json \ "source").as[String],
      state = State[Phrase]((json \ "state").as[String])
    ))
}

object PhraseSerializer {
  implicit val phraseSerializer = new PhraseSerializer
}