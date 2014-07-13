package com.keepit.abook.model

import com.keepit.common.db.{ Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ User }
import play.api.libs.json._

case class RichContact(email: EmailAddress, name: Option[String] = None, firstName: Option[String] = None, lastName: Option[String] = None, userId: Option[Id[User]] = None)
object RichContact {
  implicit val format = Json.format[RichContact]
}

