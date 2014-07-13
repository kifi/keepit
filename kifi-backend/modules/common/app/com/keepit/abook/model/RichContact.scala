package com.keepit.abook.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ ABookInfo, User }
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class RichContact(email: EmailAddress, name: Option[String] = None, firstName: Option[String] = None, lastName: Option[String] = None, userId: Option[Id[User]] = None)
object RichContact {
  implicit val format = Json.format[RichContact]
}

case class IngestableEmailAccount(emailAccountId: Id[IngestableEmailAccount], userId: Option[Id[User]], verified: Boolean, deleted: Boolean, seq: SequenceNumber[IngestableEmailAccount])
object IngestableEmailAccount {
  implicit val format = (
    (__ \ 'emailAccountId).format(Id.format[IngestableEmailAccount]) and
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'verified).format[Boolean] and
    (__ \ 'deleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[IngestableEmailAccount])
  )(IngestableEmailAccount.apply, unlift(IngestableEmailAccount.unapply))
}

case class IngestableContact(userId: Id[User], abookId: Id[ABookInfo], emailAccountId: Id[IngestableEmailAccount], hidden: Boolean, deleted: Boolean, seq: SequenceNumber[IngestableContact])
object IngestableContact {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'abookId).format(Id.format[ABookInfo]) and
    (__ \ 'emailAccountId).format(Id.format[IngestableEmailAccount]) and
    (__ \ 'hidden).format[Boolean] and
    (__ \ 'deleted).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[IngestableContact])
  )(IngestableContact.apply, unlift(IngestableContact.unapply))
}
