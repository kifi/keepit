package com.keepit.shoebox.model

import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.service.IpAddress
import com.keepit.model.User
import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class IngestableUserIpAddress(userId: Id[User], ipAddress: IpAddress, updatedAt: DateTime, seqNum: SequenceNumber[IngestableUserIpAddress])

object IngestableUserIpAddress {
  implicit val format = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'ipAddress).format[IpAddress] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'seqNum).format(SequenceNumber.format[IngestableUserIpAddress])
  )(IngestableUserIpAddress.apply, unlift(IngestableUserIpAddress.unapply))
}
