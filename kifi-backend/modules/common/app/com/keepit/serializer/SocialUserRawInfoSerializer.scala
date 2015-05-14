package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.search.Article
import securesocial.core._
import securesocial.core.AuthenticationMethod._
import play.api.libs.json._
import com.keepit.model.SocialUserInfo
import com.keepit.social.{ SocialUserRawInfo, SocialNetworkType, SocialId }

class SocialUserRawInfoSerializer extends Format[SocialUserRawInfo] {

  def writes(info: SocialUserRawInfo): JsValue =
    JsObject(List(
      "userId" -> (info.userId map { e => JsNumber(e.id) } getOrElse (JsNull)),
      "socialUserInfoId" -> JsNumber(info.socialUserInfoId.get.id),
      "socialId" -> JsString(info.socialId.id),
      "networkType" -> JsString(SocialNetworkType.unapply(info.networkType).get),
      "fullName" -> JsString(info.fullName),
      "jsons" -> JsArray(info.jsons.toList)
    )
    )

  def reads(json: JsValue): JsSuccess[SocialUserRawInfo] = JsSuccess({
    val jsons = (json \ "jsons") match {
      case array: JsArray => array.value.toStream
      case _ => Stream()
    }
    val socialId = (json \ "socialId").as[String].trim
    if (socialId.isEmpty) throw new Exception(s"empty social id for $json")
    SocialUserRawInfo(
      userId = (json \ "userId").asOpt[Long].map(Id(_)),
      socialUserInfoId = Some(Id[SocialUserInfo]((json \ "socialUserInfoId").as[Int])),
      socialId = SocialId(socialId),
      networkType = SocialNetworkType((json \ "networkType").as[String]),
      fullName = (json \ "fullName").as[String],
      jsons = jsons
    )
  })
}

object SocialUserRawInfoSerializer {
  implicit val SocialUserRawInfoSerializer = new SocialUserRawInfoSerializer
}
