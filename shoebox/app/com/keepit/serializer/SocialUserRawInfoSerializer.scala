package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.FacebookId
import com.keepit.search.Article
import com.keepit.common.social.SocialUserRawInfo
import com.keepit.model.SocialId
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._

class SocialUserRawInfoSerializer extends Format[SocialUserRawInfo] {
  
  def writes(info: SocialUserRawInfo): JsValue =
    JsObject(List(
        "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse(JsNull)),
        "socialId" -> JsString(info.socialId.id),
        "fullName" -> JsString(info.fullName),
        "json" -> info.json 
      )
    )
    
  def reads(json: JsValue): SocialUserRawInfo = SocialUserRawInfo(
      (json \ "userId").asOpt[Long].map(Id(_)), 
      SocialId((json \ "socialId").as[String]), 
      (json \ "fullName").as[String],
      (json \ "json")
   )
}

object SocialUserRawInfoSerializer {
  implicit val SocialUserRawInfoSerializer = new SocialUserRawInfoSerializer
}
