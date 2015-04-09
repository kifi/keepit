package com.keepit.social

import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.json.JsValue
import com.keepit.model.SocialUserInfo

case class SocialUserRawInfo(userId: Option[Id[User]], socialUserInfoId: Option[Id[SocialUserInfo]], socialId: SocialId, networkType: SocialNetworkType, fullName: String, jsons: Stream[JsValue])

object SocialUserRawInfo {
  def apply(socialUserInfo: SocialUserInfo, jsons: Stream[JsValue]): SocialUserRawInfo = {
    SocialUserRawInfo(userId = socialUserInfo.userId, socialUserInfoId = socialUserInfo.id,
      socialId = socialUserInfo.socialId, networkType = socialUserInfo.networkType,
      fullName = socialUserInfo.fullName, jsons = jsons)
  }

  def apply(socialUserInfo: SocialUserInfo, json: JsValue): SocialUserRawInfo = apply(socialUserInfo, Stream(json))
}
