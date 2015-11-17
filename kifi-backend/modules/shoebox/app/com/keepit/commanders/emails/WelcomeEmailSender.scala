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
import com.keepit.model._

import scala.concurrent.Future

class WelcomeEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    localUserExperimentCommander: LocalUserExperimentCommander,
    fortytwoConfig: FortyTwoConfig,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress], verificationCode: Option[EmailVerificationCode], domainOwnerIds: Set[Id[Organization]], installs: Set[KifiInstallationPlatform]): Future[ElectronicMail] = {

    val verifyUrl = verificationCode.map(code => s"${fortytwoConfig.applicationBaseUrl}${EmailVerificationCode.verifyPath(code)}")

    val emailToSend = EmailToSend(
      title = "Kifi — Welcome",
      fromName = Some(Right("Eishay Smith")),
      from = SystemEmailAddress.EISHAY_PUBLIC,
      subject = "Let's get started with Kifi",
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      category = NotificationCategory.User.WELCOME,
      htmlTemplate = views.html.email.black.welcomePlain(userId, verifyUrl, domainOwnerIds, installs),
      textTemplate = Some(views.html.email.black.welcomeText(userId, verifyUrl, domainOwnerIds, installs)),
      templateOptions = Map("layout" -> CustomLayout),
      tips = Seq.empty
    )
    emailTemplateSender.send(emailToSend)
  }
}
