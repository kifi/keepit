package com.keepit.notify.model.event

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.User
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

object NewSocialConnection extends NonGroupingNotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "friendId").format[Id[User]] and
    (__ \ "networkType").formatNullable[SocialNetworkType]
  )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

  override def shouldGroupWith(newEvent: NewSocialConnection, existingEvents: Set[NewSocialConnection]): Boolean = false

}

// todo missing, social new library through twitter (unused?)
// todo missing, social new follower through twitter (unused?)

case class SocialContactJoined(
    recipient: Recipient,
    time: DateTime,
    joinerId: Id[User]) extends SocialEvent {

  val kind = SocialContactJoined

}

object SocialContactJoined extends NonGroupingNotificationKind[SocialContactJoined] {

  override val name: String = "social_contact_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "joinerId").format[Id[User]]
  )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

  override def shouldGroupWith(newEvent: SocialContactJoined, existingEvents: Set[SocialContactJoined]): Boolean = false

}
