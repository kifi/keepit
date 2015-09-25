package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.emails.FriendConnectionMadeEmailSender
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.NewSocialConnection
import com.keepit.social.{ BasicUser, SocialNetworkType }
import com.keepit.social.SocialNetworks.{ LINKEDIN, FACEBOOK }

import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

class FriendConnectionNotifier @Inject() (
    db: Database,
    userRepo: UserRepo,
    connectionMadeEmailSender: FriendConnectionMadeEmailSender,
    s3ImageStore: S3ImageStore,
    kifiInstallationCommander: KifiInstallationCommander,
    implicit val executionContext: ExecutionContext,
    elizaServiceClient: ElizaServiceClient) {

  def sendNotification(myUserId: Id[User], friendUserId: Id[User], networkTypeOpt: Option[SocialNetworkType] = None) = {
    //sending 'you are friends' email && Notification from auto-created connections from Facebook/LinkedIn
    val (respondingUser, respondingUserImage) = db.readOnlyMaster { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val respondingUserImage = s3ImageStore.avatarUrlByUser(respondingUser)
      (respondingUser, respondingUserImage)
    }

    val isNewUserFromSocialNetwork =
      currentDateTime.minusHours(24).isBefore(respondingUser.createdAt) &&
        networkTypeOpt.exists(n => n == FACEBOOK || n == LINKEDIN)

    val category =
      if (isNewUserFromSocialNetwork) NotificationCategory.User.SOCIAL_FRIEND_JOINED
      else NotificationCategory.User.CONNECTION_MADE

    val emailF = connectionMadeEmailSender(friendUserId, myUserId, category, networkTypeOpt)

    val notificationF = elizaServiceClient.sendNotificationEvent(NewSocialConnection(
      Recipient(friendUserId),
      currentDateTime,
      myUserId,
      networkTypeOpt
    ))

    emailF flatMap (_ => notificationF)
  }

}
