package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, User }
import com.keepit.notify.info.{ NeedInfo, NotificationInfo, UsingDbSubset }
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient, NotificationEvent }
import com.keepit.social.BasicUser
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

object OrgNewInvite extends NonGroupingNotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

  override def info(event: OrgNewInvite): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.inviterId), organization(event.orgId), userImageUrl(event.inviterId)
    )) { subset =>
      val inviter = subset.user(event.inviterId)
      val invitedOrg = subset.organization(event.orgId)
      val inviterImage = subset.userImageUrl(event.inviterId)
      NotificationInfo(
        url = Path(invitedOrg.handle.value).encode.absolute,
        imageUrl = inviterImage,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${invitedOrg.name}!",
        body = s"Help ${invitedOrg.name} by sharing your knowledge with them.",
        linkText = "Visit organization"
      )
    }
  }

}

case class OrgInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User],
    orgId: Id[Organization]) extends OrgEvent {

  val kind = OrgInviteAccepted

}

object OrgInviteAccepted extends NonGroupingNotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

  override def info(event: OrgInviteAccepted): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.accepterId), organization(event.orgId), userImageUrl(event.accepterId), organizationInfo(event.orgId)
    )) { subset =>
      val accepter = subset.user(event.accepterId)
      val acceptedOrg = subset.organization(event.orgId)
      val accepterId = subset.userImageUrl(event.accepterId)
      val acceptedOrgInfo = subset.organizationInfo(event.orgId)
      NotificationInfo(
        url = Path(acceptedOrg.handle.value).encode.absolute,
        imageUrl = accepterId,
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.name}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.name}",
        linkText = "Visit organization",
        extraJson = Some(Json.obj(
          "member" -> BasicUser.fromUser(accepter),
          "organization" -> Json.toJson(acceptedOrgInfo)
        ))
      )
    }
  }

}
