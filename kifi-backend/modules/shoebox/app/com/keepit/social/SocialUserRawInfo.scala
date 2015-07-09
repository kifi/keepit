package com.keepit.social

import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.json._
import com.keepit.model.SocialUserInfo

case class SocialUserRawInfo(userId: Option[Id[User]], socialUserInfoId: Option[Id[SocialUserInfo]], socialId: SocialId, networkType: SocialNetworkType, fullName: String, jsons: Stream[JsValue])

object SocialUserRawInfo {
  def apply(socialUserInfo: SocialUserInfo, jsons: Stream[JsValue]): SocialUserRawInfo = {
    SocialUserRawInfo(userId = socialUserInfo.userId, socialUserInfoId = socialUserInfo.id,
      socialId = socialUserInfo.socialId, networkType = socialUserInfo.networkType,
      fullName = socialUserInfo.fullName, jsons = jsons)
  }

  def apply(socialUserInfo: SocialUserInfo, json: JsValue): SocialUserRawInfo = apply(socialUserInfo, Stream(json))

  val format = new Format[SocialUserRawInfo] {

    def writes(info: SocialUserRawInfo): JsValue = {
      Json.obj(
        "userId" -> info.userId,
        "socialUserInfoId" -> info.socialUserInfoId,
        "socialId" -> info.socialId,
        "networkType" -> info.networkType,
        "fullName" -> info.fullName,
        "jsons" -> info.jsons
      )
    }

    def reads(json: JsValue): JsResult[SocialUserRawInfo] = json.validate[JsObject].map { obj =>
      SocialUserRawInfo(
        userId = (obj \ "userId").asOpt[Id[User]],
        socialUserInfoId = (obj \ "socialUserInfoId").asOpt[Id[SocialUserInfo]],
        socialId = (obj \ "socialId").as[SocialId],
        networkType = (obj \ "networkType").as[SocialNetworkType],
        fullName = (obj \ "fullName").as[String],
        jsons = (obj \ "jsons").asOpt[Seq[JsValue]].map(_.toStream) getOrElse Stream()
      )
    }
  }
}
