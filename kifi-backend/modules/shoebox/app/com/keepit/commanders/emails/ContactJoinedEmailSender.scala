package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.social.BasicUserRepo
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ User, NotificationCategory, PasswordResetRepo }

import scala.concurrent.Future

class ContactJoinedEmailSender @Inject() (
    db: Database,
    basicUserRepo: BasicUserRepo,
    emailTemplateSender: EmailTemplateSender,
    config: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(toUserId: Id[User], otherUserId: Id[User]): Future[ElectronicMail] = {
    val otherUser = db.readOnlyReplica { implicit session => basicUserRepo.load(otherUserId) }
    val emailToSend = EmailToSend(
      fromName = Some(s"${otherUser.firstName} ${otherUser.lastName} (via Kifi)"),
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"${otherUser.firstName} ${otherUser.lastName} joined Kifi. Want to connect?",
      to = Left(toUserId),
      category = NotificationCategory.User.CONTACT_JOINED,
      htmlTemplate = views.html.email.contactJoinedBlack(toUserId, otherUserId),
      campaign = Some("contactJoined")
    )
    emailTemplateSender.send(emailToSend)
  }
}
