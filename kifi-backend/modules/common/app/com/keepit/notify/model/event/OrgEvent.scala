package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.model.{ Organization, User }
import com.keepit.notify.model.{ NotificationKind, Recipient, NotificationEvent }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait OrgEvent extends NotificationEvent

case class OrgNewInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User],
    orgId: Id[Organization]) extends OrgEvent {

  val kind = OrgNewInvite

}

object OrgNewInvite extends NotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

  override def shouldGroupWith(newEvent: OrgNewInvite, existingEvents: Set[OrgNewInvite]): Boolean = false

}

case class OrgInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User],
    orgId: Id[Organization]) extends OrgEvent {

  val kind = OrgInviteAccepted

}

object OrgInviteAccepted extends NotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

  override def shouldGroupWith(newEvent: OrgInviteAccepted, existingEvents: Set[OrgInviteAccepted]): Boolean = false

}
