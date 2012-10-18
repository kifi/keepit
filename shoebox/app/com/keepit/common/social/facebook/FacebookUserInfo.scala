package com.keepit.common.social.facebook

import com.keepit.model.{User, FacebookId}
import com.keepit.common.db.Id
import play.api.libs.json.JsValue

case class FacebookUserInfo(userId: Option[Id[User]], facebookId: FacebookId, name: String, json: JsValue)