package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.heimdal.{ ContextData, SimpleContextData, HeimdalContext }
import com.keepit.model.{ ExperimentType, User, NotificationCategory }
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.template.{ EmailLayout, EmailTip, EmailToSend }
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }

import scala.concurrent.Future

class WelcomeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    localUserExperimentCommander: LocalUserExperimentCommander,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress] = None, isPlainEmail: Boolean = true) = sendToUser(userId, toAddress, isPlainEmail)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress] = None, isPlainEmail: Boolean = true): Future[ElectronicMail] = {

    val usePlainEmail = isPlainEmail || localUserExperimentCommander.userHasExperiment(userId, ExperimentType.PLAIN_EMAIL)

    val emailToSend = EmailToSend(
      title = "Kifi — Welcome",
      fromName = Some(Right("Eishay Smith")),
      from = SystemEmailAddress.EISHAY_PUBLIC,
      subject = "Let's get started with Kifi",
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      category = NotificationCategory.User.WELCOME,
      htmlTemplate = if (usePlainEmail) { views.html.email.black.welcomePlain(userId) } else { views.html.email.black.welcome(userId) },
      textTemplate = Some(views.html.email.black.welcomeText(userId)),
      templateOptions = if (usePlainEmail) { Map("layout" -> CustomLayout) } else { Map.empty },
      tips = if (usePlainEmail) { Seq.empty } else { Seq(EmailTip.ConnectFacebook, EmailTip.ConnectLinkedIn) }
    // TODO(josh) add EmailTip.InstallExtension when it's complete
    )
    emailTemplateSender.send(emailToSend)
  }
}
