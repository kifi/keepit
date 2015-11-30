package com.keepit.commanders.emails

import com.google.inject.{ Inject }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.{ TemplateOptions, EmailToSend }
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, EmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ NotificationCategory, User }
import scala.concurrent.Future

class TwitterWaitlistEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(email: EmailAddress, userId: Id[User]): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      title = "Kifi — Congrats! You're on the list",
      fromName = Some(Right("Kifi")),
      from = SystemEmailAddress.NOTIFICATIONS,
      to = Right(email),
      subject = "You are on the list",
      category = NotificationCategory.User.WAITLIST,
      htmlTemplate = views.html.email.v3.twitterWaitlist(userId),
      textTemplate = Some(views.html.email.v3.twitterWaitlistText(userId)),
      templateOptions = Seq(TemplateOptions.CustomLayout).toMap,
      campaign = Some(s"twitter_waitlist"))
    emailTemplateSender.send(emailToSend)
  }
}
