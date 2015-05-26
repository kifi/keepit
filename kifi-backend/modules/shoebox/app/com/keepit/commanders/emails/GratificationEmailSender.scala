package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.commanders.emails.GratificationEmailSender.{ SenderInfo, SortedLibraryCountData }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.time._
import com.keepit.model._

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

object GratificationEmailSender {
  case class SortedLibraryCountData(totalCount: Int, countByLibrary: List[(Id[Library], Int)]) // represents the total and per-library view counts for a single user
  case class SenderInfo(firstName: String, lastName: String, title: String, addr: EmailAddress, path: String)

  val SENDER_FIRSTNAME = "Cam"
  val SENDER_LASTNAME = "Hashemi"
  val SENDER_ADDR = SystemEmailAddress.CAM
  val SENDER_PATH = "/cam"
  val SENDER_TITLE = "Engineer"
  val senderInfo = SenderInfo(SENDER_FIRSTNAME, SENDER_LASTNAME, SENDER_TITLE, SENDER_ADDR, SENDER_PATH)
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
      val sortedViewsByLibrary = SortedLibraryCountData(viewsByLibrary.totalCount, viewsByLibrary.countByLibrary.toList.sortWith(_._2 > _._2))
      val sortedFollowersByLibrary = SortedLibraryCountData(followersByLibrary.totalCount, followersByLibrary.countByLibrary.toList.sortWith(_._2 > _._2))
      val emailToSend = EmailToSend(
        from = senderInfo.addr,
        fromName = Some(Right(senderInfo.firstName + " " + senderInfo.lastName)),
        to = toAddress.map(Right.apply).getOrElse(Left(userId)),
        subject = "You've been busy this week on Kifi!",
        category = NotificationCategory.User.GRATIFICATION_EMAIL,
        htmlTemplate = views.html.email.black.gratification(userId, senderInfo, sortedViewsByLibrary, sortedFollowersByLibrary, newConnections),
        textTemplate = Some(views.html.email.black.gratificationText(userId, senderInfo, sortedViewsByLibrary, sortedFollowersByLibrary, newConnections)),
        templateOptions = Map("layout" -> CustomLayout),
        tips = Seq.empty
      )
      emailTemplateSender.send(emailToSend)
    }
  }
}

