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

  override def info(event: NewSocialConnection): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.friendId)
    )) { subset =>
      val friendInfo = RequestUser(event.friendId).lookup(subset)
      val friend = friendInfo.user
      NotificationInfo(
        url = friendInfo.path.encode.absolute,
        title = s"You’re connected with ${friend.fullName} on Kifi!",
        body = s"Enjoy ${friend.firstName}’s keeps in your search results and message ${friend.firstName} directly",
        imageUrl = friendInfo.imageUrl,
        linkText = s"View ${friend.firstName}’s profile",
        extraJson = Some(Json.obj(
          "friend" -> friend
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

  override def info(event: SocialContactJoined): RequestingNotificationInfos[NotificationInfo] = {
    import NotificationInfoRequest._
    RequestingNotificationInfos(Requests(
      RequestUser(event.joinerId)
    )) { subset =>
      val joinerInfo = RequestUser(event.joinerId).lookup(subset)
      val joiner = joinerInfo.user
      NotificationInfo(
        url = joinerInfo.path.encode.absolute,
        title = s"${joiner.firstName} ${joiner.lastName} joined Kifi!",
        body = s"To discover ${joiner.firstName}’s public keeps while searching, get connected! Invite ${joiner.firstName} to connect on Kifi »",
        linkText = s"Invite ${joiner.firstName} to connect",
        imageUrl = joinerInfo.imageUrl
      )
    }
  }

}
