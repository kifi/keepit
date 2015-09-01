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

  override def info(event: OrgNewInvite): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId), RequestOrganization(event.orgId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      val invitedOrg = RequestOrganization(event.orgId).lookup(batched)
      NotificationInfo(
        url = Path(invitedOrg.handle.value).encode.absolute,
        image = UserImage(inviter),
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

  override def info(event: OrgInviteAccepted): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId), RequestOrganization(event.orgId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      val acceptedOrg = RequestOrganization(event.orgId).lookup(batched)
      NotificationInfo(
        url = Path(acceptedOrg.handle.value).encode.absolute,
        image = UserImage(accepter),
        title = s"${accepter.firstName} accepted your invitation to join ${acceptedOrg.abbreviatedName}!",
        body = s"You invited ${accepter.firstName} to join ${acceptedOrg.abbreviatedName}",
        linkText = "Visit organization",
        extraJson = Some(Json.obj(
          "member" -> accepter,
          "organization" -> Json.toJson(acceptedOrg)
        ))
      )
    }
  }

}
