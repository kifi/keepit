package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{LocalPostOffice, EmailAddresses, ElectronicMail}
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.eliza.model.ThreadItem
import scala.Some
import com.keepit.model.DeepLocator

class EmailNotificationsCommander @Inject() (
  localPostOffice: LocalPostOffice,
  userRepo: UserRepo,
  deepLinkRepo: DeepLinkRepo,
  db: Database) {

  def sendUnreadMessages(threadItems: Seq[ThreadItem], otherParticipantIds: Seq[Id[User]],
                         recipientUserId: Id[User], title: String, deepLocator: DeepLocator): Unit = db.readWrite { implicit session =>
    //if user is not active, skip it!
    val user = userRepo.get(recipientUserId)
    val otherParticipants = otherParticipantIds map userRepo.get
    val allUsers: Map[Id[User], User] = (otherParticipants :+ user) map {u => u.id.get -> u} toMap

    if (user.state == UserStates.ACTIVE) {
      val authorFirstLast: Seq[String] = otherParticipants.map(user => user.firstName + " " + user.lastName).sorted
      val link = deepLinkRepo.getByLocatorAndUser(deepLocator, recipientUserId)
      val url = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(link.token.value).toString()
      val emailBody = views.html.email.unreadMessages(user, authorFirstLast, threadItems, url, title, allUsers).body
      val textBody = views.html.email.unreadMessagesPlain(user, authorFirstLast, threadItems, url, title, allUsers).body

      val authorFirst = otherParticipants.map(_.firstName).sorted.mkString(", ")
      val email = ElectronicMail(
        from = EmailAddresses.NOTIFICATIONS, fromName = Some("Kifi Notifications"),
        to = List(),
        subject = s"""New messages on "$title" with $authorFirst""",
        htmlBody = emailBody,
        textBody = Some(textBody),
        category = NotificationCategory.User.MESSAGE
      )
      localPostOffice.sendMail(email)
    }
  }
}
