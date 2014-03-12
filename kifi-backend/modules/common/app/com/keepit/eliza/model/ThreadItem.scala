package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class ThreadItem(userId: Id[User], message: String)

object ThreadItem {
  implicit val userIdFormat = Id.format[User]

  implicit val basicUserFormat = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'message).format[String]
  )(ThreadItem.apply, unlift(ThreadItem.unapply))

}