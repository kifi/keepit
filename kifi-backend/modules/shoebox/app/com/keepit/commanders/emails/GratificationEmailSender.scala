package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
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

  case class SenderInfo(
    firstName: String = "Cam",
    lastName: String = "Hashemi",
    addr: EmailAddress = SystemEmailAddress.CAM,
    path: String = "/cam",
    role: String = "engineer")

  val senderInfo = new SenderInfo
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

    val senderInfo = GratificationEmailSender.senderInfo

    fViewsByLibrary.flatMap { viewsByLibrary: LibraryCountData =>
      val emailToSend = EmailToSend(
        from = senderInfo.addr,
        fromName = Some(Right(senderInfo.firstName + " " + senderInfo.lastName)),
        to = toAddress.map(Right.apply).getOrElse(Left(userId)),
        subject = "You've been busy this week on Kifi!",
        category = NotificationCategory.User.GRATIFICATION_EMAIL,
        htmlTemplate = views.html.email.black.gratification(userId, senderInfo, viewsByLibrary, followersByLibrary, newConnections),
        textTemplate = Some(views.html.email.black.gratificationText(userId, senderInfo, viewsByLibrary, followersByLibrary, newConnections)),
        templateOptions = Map("layout" -> CustomLayout),
        tips = Seq.empty
      )
      emailTemplateSender.send(emailToSend)
    }
  }
}

