package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.User
import com.keepit.notify.info.ReturnsInfo.{ GetUserImage, GetUser, PickOne }
import com.keepit.notify.info._
import com.keepit.notify.model._
import com.keepit.social.{ BasicUser, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait SocialEvent extends NotificationEvent

case class NewSocialConnection(
    recipient: Recipient,
    time: DateTime,
    friendId: Id[User],
    networkType: Option[SocialNetworkType]) extends SocialEvent {

  val kind = NewSocialConnection

}

object NewSocialConnection extends NotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "friendId").format[Id[User]] and
    (__ \ "networkType").formatNullable[SocialNetworkType]
  )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

  override def shouldGroupWith(newEvent: NewSocialConnection, existingEvents: Set[NewSocialConnection]): Boolean = false

  def build(
    recipient: Recipient,
    time: DateTime,
    friend: User,
    friendImage: String,
    networkType: Option[SocialNetworkType]): EventArgs =
    EventArgs(
      NewSocialConnection(recipient, time, friend.id.get, networkType)
    ).args("friend" -> friend, "friendImage" -> friendImage)

  override def info(events: Set[NewSocialConnection]): ReturnsInfoResult = for {
    event <- PickOne(events)
    friend <- GetUser(event.friendId, "friend")
    image <- GetUserImage(event.friendId, "friendImage")
  } yield NotificationInfo(
    url = Path(friend.username.value).encode.absolute,
    imageUrl = image,
    title = s"You’re connected with ${friend.firstName} ${friend.lastName} on Kifi!",
    body = s"Enjoy ${friend.firstName}’s keeps in your search results and message ${friend.firstName} directly.",
    linkText = "Invite more friends to kifi",
    extraJson = Some(Json.obj(
      "friend" -> BasicUser.fromUser(friend)
    ))
  )

}

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
    recipient: Recipient,
    time: DateTime,
    joinerId: Id[User]) extends SocialEvent {

  val kind = SocialContactJoined

}

object SocialContactJoined extends NotificationKind[SocialContactJoined] {

  override val name: String = "social_contact_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "joinerId").format[Id[User]]
  )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

  override def shouldGroupWith(newEvent: SocialContactJoined, existingEvents: Set[SocialContactJoined]): Boolean = false

}
