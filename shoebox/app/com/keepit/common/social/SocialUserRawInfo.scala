package com.keepit.common.social

import com.keepit.model.{User, FacebookId}
import com.keepit.common.db.Id
import play.api.libs.json.JsValue
import com.keepit.model.SocialId
import com.keepit.model.SocialUserInfo

case class SocialUserRawInfo(userId: Option[Id[User]], socialId: SocialId, networkType: SocialNetworkType, fullName: String, json: JsValue) {
  def toSocialUserInfo = SocialUserInfo(userId = userId, fullName = fullName, socialId = socialId, networkType = networkType)
}