package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, User }
import com.keepit.notify.info.{ DbViewKey$, NotificationInfo, UsingDbView }
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait OrgNewInviteImpl extends NonGroupingNotificationKind[OrgNewInvite] {

  override val name: String = "org_new_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgNewInvite.apply, unlift(OrgNewInvite.unapply))

  override def info(event: OrgNewInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Seq(
      user(event.inviterId), organization(event.orgId), userImageUrl(event.inviterId)
    )) { subset =>
      val inviter = user(event.inviterId).lookup(subset)
      val invitedOrg = organization(event.orgId).lookup(subset)
      val inviterImage = userImageUrl(event.inviterId).lookup(subset)
      NotificationInfo(
        url = Path(invitedOrg.handle.value).encode.absolute,
        imageUrl = inviterImage,
        title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${invitedOrg.abbreviatedName}!",
        body = s"Help ${invitedOrg.abbreviatedName} by sharing your knowledge with them.",
        linkText = "Visit organization"
      )
    }
  }

}

trait OrgInviteAcceptedImpl extends NonGroupingNotificationKind[OrgInviteAccepted] {

  override val name: String = "org_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]] and
    (__ \ "orgId").format[Id[Organization]]
  )(OrgInviteAccepted.apply, unlift(OrgInviteAccepted.unapply))

  override def info(event: OrgInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Seq(
      user(event.accepterId), organization(event.orgId), userImageUrl(event.accepterId), organizationInfo(event.orgId)
    )) { subset =>
      val accepter = user(event.accepterId).lookup(subset)
      val acceptedOrg = organization(event.orgId).lookup(subset)
      val accepterId = userImageUrl(event.accepterId).lookup(subset)
      val acceptedOrgInfo = organizationInfo(event.orgId).lookup(subset)
      NotificationInfo(
        url = Path(acceptedOrg.handle.value).encode.absolute,
        imageUrl = accepterId,
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.abbreviatedName}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.abbreviatedName}",
        linkText = "Visit organization",
        extraJson = Some(Json.obj(
          "member" -> BasicUser.fromUser(accepter),
          "organization" -> Json.toJson(acceptedOrgInfo)
        ))
      )
    }
  }

}
