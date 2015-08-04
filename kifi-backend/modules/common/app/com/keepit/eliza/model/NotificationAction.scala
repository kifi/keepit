package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.{Library, User}
import org.joda.time.DateTime
import play.api.libs.json._

class NotificationAction(kind: NotificationActionKind[_], fromUser: Id[User], toUser: Id[User], time: DateTime, data: Option[JsObject])
