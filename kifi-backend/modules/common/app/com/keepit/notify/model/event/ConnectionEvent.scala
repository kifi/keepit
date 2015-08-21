package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.User
import com.keepit.notify.info._
import com.keepit.notify.model.{ NonGroupingNotificationKind, NotificationKind, Recipient, NotificationEvent }
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future

trait ConnectionEvent extends NotificationEvent

case class NewConnectionInvite(
    recipient: Recipient,
    time: DateTime,
    inviterId: Id[User]) extends ConnectionEvent {

  val kind = NewConnectionInvite

}

object NewConnectionInvite extends NonGroupingNotificationKind[NewConnectionInvite] {

  override val name: String = "new_connection_invite"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "inviterId").format[Id[User]]
  )(NewConnectionInvite.apply, unlift(NewConnectionInvite.unapply))

  override def info(event: NewConnectionInvite): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.inviterId), userImageUrl(event.inviterId)
    )) { subset =>
      val inviter = subset.user(event.inviterId)
      val inviterImage = subset.userImageUrl(event.inviterId)
      NotificationInfo(
        url = Path(inviter.username.value).encode.absolute,
        title = s"${inviter.firstName} ${inviter.lastName} wants to connect with you on Kifi",
        body = s"Enjoy ${inviter.firstName}’s keeps in your search results and message ${inviter.firstName} directly.",
        linkText = s"Respond to ${inviter.firstName}’s invitation",
        imageUrl = inviterImage,
        extraJson = Some(Json.obj(
          "friend" -> BasicUser.fromUser(inviter)
        ))
      )
    }
  }

}

case class ConnectionInviteAccepted(
    recipient: Recipient,
    time: DateTime,
    accepterId: Id[User]) extends ConnectionEvent {

  val kind = ConnectionInviteAccepted

}

object ConnectionInviteAccepted extends NonGroupingNotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]]
  )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

  override def info(event: ConnectionInviteAccepted): UsingDbSubset[NotificationInfo] = {
    import NeedInfo._
    UsingDbSubset(Seq(
      user(event.accepterId), userImageUrl(event.accepterId)
    )) { subset =>
      val accepter = subset.user(event.accepterId)
      val accepterImage = subset.userImageUrl(event.accepterId)
      NotificationInfo(
        url = Path(accepter.username.value).encode.absolute,
        title = s"${accepter.firstName} ${accepter.lastName} accepted your invitation to connect!",
        body = s"Now you will enjoy ${accepter.firstName}’s keeps in your search results and message ${accepter.firstName} directly.",
        linkText = s"Visit ${accepter.firstName}’s profile",
        imageUrl = accepterImage,
        extraJson = Some(Json.obj(
          "friend" -> BasicUser.fromUser(accepter)
        ))
      )
    }
  }

}
