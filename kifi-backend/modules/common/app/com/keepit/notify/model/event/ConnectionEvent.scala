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

  def build(recipient: Recipient, time: DateTime, inviter: User): ExistingDbView[NewConnectionInvite] = {
    import DbViewKey._
    ExistingDbView(Existing(
      user.existing(inviter)
    ))(NewConnectionInvite(
      recipient = recipient,
      time = time,
      inviterId = inviter.id.get
    ))
  }

  override def info(event: NewConnectionInvite): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.inviterId), userImageUrl(event.inviterId)
    )) { subset =>
      val inviter = user(event.inviterId).lookup(subset)
      val inviterImage = userImageUrl(event.inviterId).lookup(subset)
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

trait ConnectionInviteAcceptedImpl extends NonGroupingNotificationKind[ConnectionInviteAccepted] {

  override val name: String = "connection_invite_accepted"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "accepterId").format[Id[User]]
  )(ConnectionInviteAccepted.apply, unlift(ConnectionInviteAccepted.unapply))

  def build(recipient: Recipient, time: DateTime, accepter: User): ExistingDbView[ConnectionInviteAccepted] = {
    import DbViewKey._
    ExistingDbView(Existing(
      user.existing(accepter)
    ))(ConnectionInviteAccepted(
      recipient = recipient,
      time = time,
      accepterId = accepter.id.get
    ))
  }

  override def info(event: ConnectionInviteAccepted): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Requests(
      user(event.accepterId), userImageUrl(event.accepterId)
    )) { subset =>
      val accepter = user(event.accepterId).lookup(subset)
      val accepterImage = userImageUrl(event.accepterId).lookup(subset)
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
