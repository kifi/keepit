package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.emails.FriendConnectionMadeEmailSender
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.model._
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

    val canSendPush = kifiInstallationCommander.isMobileVersionGreaterThen(friendUserId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
    if (canSendPush) {
      elizaServiceClient.sendUserPushNotification(
        userId = friendUserId,
        message = s"${respondingUser.fullName} connected to you on Kifi",
        recipient = respondingUser,
        pushNotificationExperiment = PushNotificationExperiment.Experiment1,
        category = UserPushNotificationCategory.UserConnectionAccepted)
    }

    val notificationF = elizaServiceClient.sendGlobalNotification( //push needed
      userIds = Set(friendUserId),
      title = s"You’re connected with ${respondingUser.firstName} ${respondingUser.lastName} on Kifi!",
      body = s"Enjoy ${respondingUser.firstName}’s keeps in your search results and message ${respondingUser.firstName} directly. Find and invite more connections »",
      linkText = "Invite more friends to kifi",
      linkUrl = "https://www.kifi.com/friends/invite",
      imageUrl = respondingUserImage,
      sticky = false,
      category = category,
      extra = Some(Json.obj("friend" -> BasicUser.fromUser(respondingUser)))
    )

    emailF flatMap (_ => notificationF)
  }

}
