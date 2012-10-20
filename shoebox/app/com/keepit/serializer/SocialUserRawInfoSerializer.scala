package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.social.{SocialNetworks, SocialId, SocialUserRawInfo}
import com.keepit.search.Article
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.model.SocialUserInfo

class SocialUserRawInfoSerializer extends Format[SocialUserRawInfo] {
  
  def writes(info: SocialUserRawInfo): JsValue =
    JsObject(List(
        "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse(JsNull)),
        "socialUserInfoId" -> JsNumber(info.socialUserInfoId.get.id),
        "socialId" -> JsString(info.socialId.id),
        "networkType" -> JsString(info.networkType.name),
        "fullName" -> JsString(info.fullName),
        "json" -> info.json 
      )
    )
    
  def reads(json: JsValue): SocialUserRawInfo = SocialUserRawInfo(
      userId = (json \ "userId").asOpt[Long].map(Id(_)), 
      socialUserInfoId = Some(Id[SocialUserInfo]((json \ "socialUserInfoId").as[Int])), 
      socialId = SocialId((json \ "socialId").as[String]), 
      networkType = (json \ "socialId").as[String] match {
        case SocialNetworks.FACEBOOK.name => SocialNetworks.FACEBOOK 
      },
      (json \ "fullName").as[String],
      (json \ "json")
   )
}

object SocialUserRawInfoSerializer {
  implicit val SocialUserRawInfoSerializer = new SocialUserRawInfoSerializer
}
