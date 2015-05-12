package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.heimdal.{ ContextData, SimpleContextData, HeimdalContext }
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.{ EmailLayout, EmailTip, EmailToSend }
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.model.NotificationCategory

import scala.concurrent.Future

class WelcomeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress] = None) = sendToUser(userId)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress] = None, isPlainEmail: Boolean = true): Future[ElectronicMail] = {
    val emailToSend = EmailToSend(
      title = "Kifi — Welcome",
      fromName = Some(Right("Kifi")),
      from = SystemEmailAddress.EISHAY_PUBLIC,
      subject = "Let's get started with Kifi",
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      category = NotificationCategory.User.WELCOME,
      htmlTemplate = if (isPlainEmail) { views.html.email.black.welcomePlain(userId) } else { views.html.email.black.welcome(userId) },
      textTemplate = Some(views.html.email.black.welcomeText(userId)),
      templateOptions = { if (isPlainEmail) { Map("layout" -> CustomLayout) } else { Map.empty } },
      tips = { if (isPlainEmail) { Seq.empty } else { Seq(EmailTip.ConnectFacebook, EmailTip.ConnectLinkedIn) } }
      // TODO(josh) add EmailTip.InstallExtension when it's complete
    )

    emailTemplateSender.send(emailToSend)
  }
}
