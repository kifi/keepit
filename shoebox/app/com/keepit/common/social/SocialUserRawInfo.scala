package com.keepit.common.social

import com.keepit.model.{User, FacebookId}
import com.keepit.common.db.Id
import play.api.libs.json.JsValue
import com.keepit.model.SocialId

case class SocialUserRawInfo(userId: Option[Id[User]], socialId: SocialId, fullName: String, json: JsValue)