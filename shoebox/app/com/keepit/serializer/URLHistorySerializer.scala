package com.keepit.serializer

import com.keepit.common.db.{Id, ExternalId, State}
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.Lang
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.common.analytics.reports._
import play.api.libs.json.JsArray
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber
import org.joda.time.DateTime

class URLHistorySerializer extends Format[Seq[URLHistory]] {

  def writes(history: Seq[URLHistory]): JsValue =
    JsArray(history map ( r => JsObject(
        Seq("date" -> Json.toJson(r.date), "id" -> JsNumber(r.id.id), "cause" -> JsString(r.cause.value))
    )))

  def reads(json: JsValue): JsResult[Seq[URLHistory]] = 
    JsSuccess((json \ "history").asOpt[List[JsObject]].getOrElse(Nil).map { h =>
      val date = (h \ "date").as[DateTime]
      val id = Id[NormalizedURI]((h \ "id").as[Int])
      val cause = URLHistoryCause((h \ "cause").as[String])
      URLHistory(date, id, cause)
    })
      
}

object URLHistorySerializer {
  implicit val urlHistorySerializer = new URLHistorySerializer
}

