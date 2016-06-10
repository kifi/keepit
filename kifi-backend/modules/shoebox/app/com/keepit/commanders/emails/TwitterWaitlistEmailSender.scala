package com.keepit.commanders.emails

import java.net.URLEncoder

import com.google.inject.{ Inject }
import com.keepit.common.db.Id
import com.keepit.common.strings.UTF8
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

  def sendToUser(email: EmailAddress, userId: Id[User], twitterLibUrl: String, count: Int): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      title = "Kifi — Congrats! You're on the list",
      fromName = Some(Right("Kifi")),
      from = SystemEmailAddress.EISHAY_PUBLIC,
      to = Right(email),
      subject = s"Done!  Your Twitter Library is ready with $count keeps.  Want Your “Liked” Links too?",
      category = NotificationCategory.User.WAITLIST,
      htmlTemplate = views.html.email.black.twitterAccept(userId, twitterLibUrl, URLEncoder.encode(twitterLibUrl, UTF8), count),
      textTemplate = None,
      templateOptions = Seq(TemplateOptions.CustomLayout).toMap,
      campaign = Some(s"twitter_waitlist"))
    emailTemplateSender.send(emailToSend)
  }
}
