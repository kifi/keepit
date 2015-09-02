package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.User
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future

trait NewConnectionInviteImpl extends NonGroupingNotificationKind[NewConnectionInvite] {

  override val name: String = "new_connection_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

  override def info(event: NewConnectionInvite): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.inviterId)
    )) { batched =>
      val inviter = RequestUser(event.inviterId).lookup(batched)
      NotificationInfo(
        user = inviter,
        title = s"${inviter.firstName} ${inviter.lastName} wants to connect with you on Kifi",
        body = s"Enjoy ${inviter.firstName}’s keeps in your search results and message ${inviter.firstName} directly.",
        linkText = s"Respond to ${inviter.firstName}’s invitation",
        extraJson = Some(Json.obj(
          "friend" -> inviter
        )),
        category = None
      )
    }
  }

}

trait ConnectionInviteAcceptedImpl extends NonGroupingNotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]]
  )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

  override def info(event: ConnectionInviteAccepted): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.accepterId)
    )) { batched =>
      val accepter = RequestUser(event.accepterId).lookup(batched)
      NotificationInfo(
        user = accepter,
        title = s"${accepter.firstName} ${accepter.lastName} accepted your invitation to connect!",
        body = s"Now you will enjoy ${accepter.firstName}’s keeps in your search results and message ${accepter.firstName} directly.",
        linkText = s"Visit ${accepter.firstName}’s profile",
        extraJson = Some(Json.obj(
          "friend" -> accepter
        )),
        category = None
      )
    }
  }

}
