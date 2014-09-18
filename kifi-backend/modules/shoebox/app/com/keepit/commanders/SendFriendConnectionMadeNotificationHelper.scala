package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.commanders.emails.FriendConnectionMadeEmailSender
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ NotificationCategory, User, UserRepo }

import play.api.libs.concurrent.Execution.Implicits.defaultContext

class SendFriendConnectionMadeNotificationHelper @Inject() (
    db: Database,
    userRepo: UserRepo,
    connectionMadeEmailSender: FriendConnectionMadeEmailSender,
    s3ImageStore: S3ImageStore,
    elizaServiceClient: ElizaServiceClient) {

  def apply(myUserId: Id[User], friendUserId: Id[User]) = {
    //sending 'you are friends' email && Notification from auto-created connections from Facebook/LinkedIn
    val emailF = connectionMadeEmailSender(friendUserId, myUserId, NotificationCategory.User.CONNECTION_MADE)

    val (respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val respondingUserImage = s3ImageStore.avatarUrlByUser(respondingUser)
      (respondingUser, respondingUserImage)
    }

    val notificationF = elizaServiceClient.sendGlobalNotification(
      userIds = Set(friendUserId),
      title = s"You’re friends with ${respondingUser.firstName} ${respondingUser.lastName} on Kifi! ",
      body = s"Enjoy ${respondingUser.firstName}'s keeps in your search results and message ${respondingUser.firstName} directly. Find and invite more friends »",
      linkText = "Invite more friends to kifi",
      linkUrl = "https://www.kifi.com/friends/invite",
      imageUrl = respondingUserImage,
      sticky = false,
      category = NotificationCategory.User.CONNECTION_MADE
    )

    emailF flatMap (_ => notificationF)
  }

}
