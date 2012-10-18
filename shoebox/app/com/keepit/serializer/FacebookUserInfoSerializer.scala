package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.social.facebook.FacebookUserInfo
import com.keepit.model.FacebookId
import com.keepit.search.Article
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._

class FacebookUserInfoSerializer extends Format[Article] {
  
  def writes(info: FacebookUserInfo): JsValue =
    JsObject(List(
        "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse(JsNull)),
        "facebookId" -> JsString(info.facebookId.value),
        "name" -> JsString(info.name),
        "facebookJson" -> info.json 
      )
    )
    
  def reads(json: JsValue): FacebookUserInfo = FacebookUserInfo(
      (json \ "userId").asOpt[Long].map(Id(_)), 
      FacebookId((json \ "facebookId").as[String]), 
      (json \ "name").as[String],
      (json \ "facebookJson")
   )
}

object FacebookUserInfoSerializer {
  implicit val facebookUserInfoSerializer = new FacebookUserInfoSerializer
}
