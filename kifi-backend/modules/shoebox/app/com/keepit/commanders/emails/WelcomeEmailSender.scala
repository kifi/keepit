package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.template.{ EmailTip, EmailToSend }
import com.keepit.common.mail.{ ElectronicMail, EmailAddress, SystemEmailAddress }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ Organization, EmailVerificationCode, UserExperimentType, NotificationCategory, User }

import scala.concurrent.Future

class WelcomeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    localUserExperimentCommander: LocalUserExperimentCommander,
    fortytwoConfig: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress], isPlainEmail: Boolean = true, verificationCode: Option[EmailVerificationCode], domainOwnerId: Option[Id[Organization]]) = sendToUser(userId, toAddress, isPlainEmail, verificationCode, domainOwnerId)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress] = None, isPlainEmail: Boolean = true, verificationCode: Option[EmailVerificationCode] = None, domainOwnerId: Option[Id[Organization]]): Future[ElectronicMail] = {

    val usePlainEmail = isPlainEmail || localUserExperimentCommander.userHasExperiment(userId, UserExperimentType.PLAIN_EMAIL)
    val verifyUrl = verificationCode.map(code => s"${fortytwoConfig.applicationBaseUrl}${EmailVerificationCode.verifyPath(code)}")

    val emailToSend = EmailToSend(
      title = "Kifi — Welcome",
      fromName = Some(Right("Eishay Smith")),
      from = SystemEmailAddress.EISHAY_PUBLIC,
      subject = "Let's get started with Kifi",
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      category = NotificationCategory.User.WELCOME,
      htmlTemplate = if (usePlainEmail) { views.html.email.black.welcomePlain(userId, verifyUrl, domainOwnerId) } else { views.html.email.black.welcome(userId, verifyUrl, domainOwnerId) },
      textTemplate = Some(views.html.email.black.welcomeText(userId, verifyUrl, domainOwnerId)),
      templateOptions = if (usePlainEmail) { Map("layout" -> CustomLayout) } else { Map.empty },
      tips = if (usePlainEmail) { Seq.empty } else { Seq(EmailTip.ConnectFacebook) }
    // TODO(josh) add EmailTip.InstallExtension when it's complete
    )
    emailTemplateSender.send(emailToSend)
  }
}
