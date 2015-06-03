package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.commanders.emails.GratificationEmailSender.SenderInfo
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.model._

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

object GratificationEmailSender {

  object SenderInfo {
    val FIRSTNAME = "Cam"
    val LASTNAME = "Hashemi"
    val ADDR = SystemEmailAddress.CAM
    val PATH = "/cam"
    val ROLE = "engineer"
  }
}

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    gratificationCommander: GratificationCommander,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress]): Future[ElectronicMail] = {

    val fViewsByLibrary: Future[LibraryCountData] = gratificationCommander.getLibraryViewData(userId)
    val followersByLibrary: LibraryCountData = gratificationCommander.getLibraryFollowerCounts(userId)
    val newConnections: Seq[Id[User]] = gratificationCommander.getNewConnections(userId)

    fViewsByLibrary.flatMap { viewsByLibrary: LibraryCountData =>
      val emailToSend = EmailToSend(
        from = SenderInfo.ADDR,
        fromName = Some(Right(SenderInfo.FIRSTNAME + " " + SenderInfo.LASTNAME)),
        to = toAddress.map(Right.apply).getOrElse(Left(userId)),
        subject = "You've been busy this week on Kifi!",
        category = NotificationCategory.User.GRATIFICATION_EMAIL,
        htmlTemplate = views.html.email.black.gratification(userId, viewsByLibrary, followersByLibrary, newConnections),
        textTemplate = Some(views.html.email.black.gratificationText(userId, viewsByLibrary, followersByLibrary, newConnections)),
        templateOptions = Map("layout" -> CustomLayout),
        tips = Seq.empty
      )
      emailTemplateSender.send(emailToSend)
    }
  }
}

