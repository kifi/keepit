package com.keepit.common.social

import com.keepit.model.User
import com.keepit.common.db.Id
import play.api.libs.json.JsValue
import com.keepit.model.SocialUserInfo

case class SocialUserRawInfo(userId: Option[Id[User]], socialUserInfoId: Option[Id[SocialUserInfo]], socialId: SocialId, networkType: SocialNetworkType, fullName: String, jsons: Seq[JsValue]) {
  def toSocialUserInfo = socialUserInfoId match {
    case Some(id) => throw new Exception("socialUserInfo with id %s already exist!".format(socialUserInfoId))
    case None => SocialUserInfo(userId = userId, fullName = fullName, socialId = socialId, networkType = networkType)
  }
}

object SocialUserRawInfo {
  def apply(socialUserInfo: SocialUserInfo, jsons: Seq[JsValue]): SocialUserRawInfo = {
    SocialUserRawInfo(userId = socialUserInfo.userId, socialUserInfoId = socialUserInfo.id,
                      socialId = socialUserInfo.socialId, networkType = socialUserInfo.networkType,
                      fullName = socialUserInfo.fullName, jsons = jsons)
  }

  def apply(socialUserInfo: SocialUserInfo, json: JsValue): SocialUserRawInfo = apply(socialUserInfo, Seq(json))
}
