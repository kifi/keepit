package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{LocalPostOffice, SystemEmailAddress, ElectronicMail}
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.eliza.model.ThreadItem
import scala.Some
import com.keepit.model.DeepLocator
import com.keepit.common.logging.Logging
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier

class EmailNotificationsCommander @Inject() (
  localPostOffice: LocalPostOffice,
  userRepo: UserRepo,
  deepLinkRepo: DeepLinkRepo,
  emailRepo: UserEmailAddressRepo,
  db: Database,
  clock: Clock,
  airbrake: AirbrakeNotifier) extends Logging {

  //Todo: needs to support non user participants
  def sendUnreadMessages(threadItems: Seq[ThreadItem], otherParticipantIds: Seq[Id[User]],
                         recipientUserId: Id[User], title: String, deepLocator: DeepLocator,
                         notificationUpdatedAt: Option[DateTime]): Unit = db.readWrite { implicit session =>
    notificationUpdatedAt foreach { time =>
      val now = clock.now
      airbrake.verify(time.isAfter(now.minusMinutes(30)), s"notificationUpdatedAt $time of thread was more then 30min ago. " +
        s"recipientUserId: $recipientUserId, deepLocator: $deepLocator")
    }
    //if user is not active, skip it!
    val recipient = userRepo.get(recipientUserId)
    if (recipient.state == UserStates.ACTIVE) {
      val otherParticipants = otherParticipantIds map userRepo.get
      val allUsers: Map[Id[User], User] = (otherParticipants :+ recipient) map {u => u.id.get -> u} toMap
      val authorFirstLast: Seq[String] = otherParticipants.map(user => user.firstName + " " + user.lastName).sorted
      val link = deepLinkRepo.getByLocatorAndUser(deepLocator, recipientUserId)
      val url = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(link.token.value).toString()
      val emailBody = views.html.email.unreadMessages(recipient, authorFirstLast, threadItems, url, title, allUsers).body
      val textBody = views.html.email.unreadMessagesPlain(recipient, authorFirstLast, threadItems, url, title, allUsers).body

      val authorFirst = otherParticipants.map(_.firstName).sorted.mkString(", ")
      val destinationEmail = emailRepo.getByUser(recipient.id.get)(session)
      val email = ElectronicMail(
        from = SystemEmailAddress.NOTIFICATIONS, fromName = Some("Kifi Notifications"),
        to = List(destinationEmail),
        subject = s"""New messages on "$title" with $authorFirst""",
        htmlBody = emailBody,
        textBody = Some(textBody),
        category = NotificationCategory.User.MESSAGE
      )
      localPostOffice.sendMail(email)
    } else {
      log.warn(s"user $recipient is not active, not sending emails")
    }
  }
}
