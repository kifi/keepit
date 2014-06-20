package com.keepit.abook.model

import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.User
import com.keepit.common.mail.EmailAddress
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class EmailAccountUpdate(emailAddress: EmailAddress, userId: Id[User], verified: Boolean, deleted: Boolean, seq: SequenceNumber[EmailAccountUpdate])

object EmailAccountUpdate {
  implicit val format = (
    (__ \ 'emailAddress).format[EmailAddress] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'verified).format[Boolean] and
    (__ \ 'deleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[EmailAccountUpdate])
  )(EmailAccountUpdate.apply, unlift(EmailAccountUpdate.unapply))
}
