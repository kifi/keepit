package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.json._
import play.api.libs.functional.syntax._

//Todo Stephen: Change the String typing for nonUser
case class ThreadItem(userId: Option[Id[User]], nonUser: Option[String], message: String)

object ThreadItem {
  implicit val userIdFormat = Id.format[User]

  implicit val basicUserFormat = (
    (__ \ 'userId).formatNullable[Id[User]] and
    (__ \ 'nonUser).formatNullable[String] and
    (__ \ 'message).format[String]
  )(ThreadItem.apply, unlift(ThreadItem.unapply))

}
