package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.commanders.emails.EmailOptOutCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ ElectronicMail, LocalPostOffice, SystemEmailAddress }
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.{ NotificationCategory, User, UserEmailAddressRepo, UserRepo }

class SendFriendConnectionMadeNotificationHelper @Inject() (
    db: Database,
    userRepo: UserRepo,
    emailRepo: UserEmailAddressRepo,
    postOffice: LocalPostOffice,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    elizaServiceClient: ElizaServiceClient) {

  def apply(myUserId: Id[User], friendUserId: Id[User]) = {
    //sending 'you are friends' email && Notification from auto-created connections from Facebook/LinkedIn
    val (friend, respondingUser, respondingUserImage) = db.readWrite { implicit session =>
      val respondingUser = userRepo.get(myUserId)
      val friend = userRepo.get(friendUserId)
      val destinationEmail = emailRepo.getByUser(friendUserId)
      val respondingUserImage = s3ImageStore.avatarUrlByUser(respondingUser)
      val targetUserImage = s3ImageStore.avatarUrlByUser(friend)
      val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(destinationEmail))}"

      postOffice.sendMail(ElectronicMail(
        senderUserId = None,
        from = SystemEmailAddress.NOTIFICATIONS,
        fromName = Some(s"${respondingUser.firstName} ${respondingUser.lastName} (via Kifi)"),
        to = List(destinationEmail),
        subject = s"You are now friends with ${respondingUser.firstName} ${respondingUser.lastName} on Kifi!",
        htmlBody = views.html.email.friendConnectionMadeInlined(friend.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body,
        textBody = Some(views.html.email.friendConnectionMadeText(friend.firstName, respondingUser.firstName, respondingUser.lastName, targetUserImage, respondingUserImage, unsubLink).body),
        category = NotificationCategory.User.FRIEND_ACCEPTED)
      )

      (friend, respondingUser, respondingUserImage)
    }

    elizaServiceClient.sendGlobalNotification(
      userIds = Set(friend.id.get),
      title = s"You’re friends with ${respondingUser.firstName} ${respondingUser.lastName} on Kifi! ",
      body = s"Enjoy ${respondingUser.firstName}'s keeps in your search results and message ${respondingUser.firstName} directly. Find and invite more friends »",
      linkText = "Invite more friends to kifi",
      linkUrl = "https://www.kifi.com/friends/invite",
      imageUrl = respondingUserImage,
      sticky = false,
      category = NotificationCategory.User.FRIEND_ACCEPTED
    )
  }

}
