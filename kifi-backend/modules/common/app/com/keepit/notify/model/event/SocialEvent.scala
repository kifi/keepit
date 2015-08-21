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

trait NewSocialConnectionImpl extends NonGroupingNotificationKind[NewSocialConnection] {

  override val name: String = "new_social_connection"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "friendId").format[Id[User]] and
    (__ \ "networkType").formatNullable[SocialNetworkType]
  )(NewSocialConnection.apply, unlift(NewSocialConnection.unapply))

  def build(recipient: Recipient, time: DateTime, friend: User, networkType: Option[SocialNetworkType]: ExistingDbView[NewSocialConnection] = {
    import DbViewKey._
    ExistingDbView(
      Seq(user.existing(friend))
    )(NewSocialConnection(
      recipient = recipient,
      time = time,
      friendId = friend.id.get,
      networkType = networkType
    ))
  }

  override def info(event: NewSocialConnection): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Seq(
      user(event.friendId), userImageUrl(event.friendId)
    )) { subset =>
      val friend = user(event.friendId).lookup(subset)
      val friendImage = userImageUrl(event.friendId).lookup(subset)
      NotificationInfo(
        url = Path(friend.username.value).encode.absolute,
        title = s"You’re connected with ${friend.fullName} on Kifi!",
        body = s"Enjoy ${friend.firstName}’s keeps in your search results and message ${friend.firstName} directly",
        imageUrl = friendImage,
        linkText = s"View ${friend.firstName}’s profile",
        extraJson = Some(Json.obj(
          "friend" -> BasicUser.fromUser(friend)
        ))
      )
    }
  }

}

trait SocialContactJoinedImpl extends NonGroupingNotificationKind[SocialContactJoined] {

  override val name: String = "social_contact_joined"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "joinerId").format[Id[User]]
  )(SocialContactJoined.apply, unlift(SocialContactJoined.unapply))

  def build(recipient: Recipient, time: DateTime, joiner: User): ExistingDbView[SocialContactJoined] = {
    import DbViewKey._
    ExistingDbView(
      Seq(user.existing(joiner))
    )(SocialContactJoined(
      recipient = recipient,
      time = time,
      joinerId = joiner.id.get
    ))
  }

  override def info(event: SocialContactJoined): UsingDbView[NotificationInfo] = {
    import DbViewKey._
    UsingDbView(Seq(
      user(event.joinerId), userImageUrl(event.joinerId)
    )) { subset =>
      val joiner = user(event.joinerId).lookup(subset)
      val joinerImage = userImageUrl(event.joinerId).lookup(subset)
      NotificationInfo(
        url = Path(joiner.username.value + "?intent=connect").encode.absolute,
        title = s"${joiner.firstName} ${joiner.lastName} joined Kifi!",
        body = s"To discover ${joiner.firstName}’s public keeps while searching, get connected! Invite ${joiner.firstName} to connect on Kifi »",
        linkText = s"Invite ${joiner.firstName} to connect",
        imageUrl = joinerImage
      )
    }
  }

}
