package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, User }
import com.keepit.notify.info._
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
    UsingDbView(Requests(
      user(event.inviterId), organization(event.orgId)
    )) { subset =>
      val inviterInfo = user(event.inviterId).lookup(subset)
      val inviter = inviterInfo.user
      val invitedOrg = organization(event.orgId).lookup(subset)
      NotificationInfo(
        url = invitedOrg.path.encode.absolute,
        imageUrl = inviterInfo.imageUrl,
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
    UsingDbView(Requests(
      user(event.accepterId), organization(event.orgId)
    )) { subset =>
      val accepterInfo = user(event.accepterId).lookup(subset)
      val accepter = accepterInfo.user
      val acceptedOrg = organization(event.orgId).lookup(subset)
      NotificationInfo(
        url = acceptedOrg.path.encode.absolute,
        imageUrl = accepterInfo.imageUrl,
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.abbreviatedName}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.abbreviatedName}",
        linkText = "Visit organization",
        extraJson = Some(Json.obj(
          "member" -> accepter,
          "organization" -> Json.toJson(acceptedOrgInfo)
        ))
      )
    }
  }

}
