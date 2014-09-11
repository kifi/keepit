package com.keepit.abook.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ ABookInfo, User }
import com.kifi.macros.json
import play.api.libs.json._
import play.api.libs.functional.syntax._

@json case class RichContact(email: EmailAddress, name: Option[String] = None, firstName: Option[String] = None, lastName: Option[String] = None, userId: Option[Id[User]] = None)

case class EmailAccountInfo(emailAccountId: Id[EmailAccountInfo], address: EmailAddress, userId: Option[Id[User]], verified: Boolean, seq: SequenceNumber[EmailAccountInfo])
object EmailAccountInfo {
  implicit val format = (
    (__ \ 'emailAccountId).format(Id.format[EmailAccountInfo]) and
    (__ \ 'address).format[EmailAddress] and
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'verified).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[EmailAccountInfo])
  )(EmailAccountInfo.apply, unlift(EmailAccountInfo.unapply))
}

case class IngestableContact(userId: Id[User], abookId: Id[ABookInfo], emailAccountId: Id[EmailAccountInfo], hidden: Boolean, deleted: Boolean, seq: SequenceNumber[IngestableContact])
object IngestableContact {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'abookId).format(Id.format[ABookInfo]) and
    (__ \ 'emailAccountId).format(Id.format[EmailAccountInfo]) and
    (__ \ 'hidden).format[Boolean] and
    (__ \ 'deleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[IngestableContact])
  )(IngestableContact.apply, unlift(IngestableContact.unapply))
}
