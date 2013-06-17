package com.keepit.realtime

import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.PostOffice
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.mail.EmailAddresses
import com.keepit.serializer.CommentWithBasicUserSerializer._
import com.keepit.common.social.CommentWithBasicUserRepo
import com.keepit.common.db.slick.DBSession._
import play.api.libs.json._
import com.keepit.common.social.BasicUser
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.UserNotificationDetails
import org.joda.time.DateTime
import com.keepit.model.UserNotificationDetails
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.ExternalId
import com.keepit.common.logging._
import com.keepit.common.net.URINormalizer
import com.keepit.common.db.{ State, Id }
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.social.ThreadInfoRepo
import com.keepit.serializer.ThreadInfoSerializer._
import com.keepit.common.healthcheck._
import com.keepit.common.akka._
import com.keepit.common.time._
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorFactory
import akka.util.Timeout
import scala.concurrent.duration._
import com.keepit.common.mail.LocalPostOffice

case object SendEmails
case class MessageNotification(notice: UserNotification)
case class CommentNotification(notice: UserNotification)

class UserEmailNotifierActor @Inject() (
  healthcheck: HealthcheckPlugin,
  userRepo: UserRepo,
  emailAddressRepo: EmailAddressRepo,
  postOffice: LocalPostOffice,
  commentFormatter: CommentFormatter,
  userNotifyRepo: UserNotificationRepo,
  clock: Clock,
  commentRepo: CommentRepo,
  commentReadRepo: CommentReadRepo,
  db: Database,
  commentRecipientRepo: CommentRecipientRepo,
  userExperimentRepo: UserExperimentRepo) extends FortyTwoActor(healthcheck) with Logging {

  implicit val commentDetailsFormat = Json.format[CommentDetails]
  implicit val messageDetailsFormat = Json.format[MessageDetails]

  def receive = {
    case SendEmails =>
      db.readOnly { implicit session =>
        log.info("Checking for notification emails to send")
        userNotifyRepo.allUndelivered(clock.now.minusMinutes(5)) foreach { notice =>
          log.info(s"Need to send email for id ${notice.id}")
          notice.category match {
            case UserNotificationCategories.MESSAGE =>
              self ! MessageNotification(notice)
            case UserNotificationCategories.COMMENT =>
              self ! CommentNotification(notice)
          }
        }
      }
    case MessageNotification(notice) =>
      log.info(s"Message notification (${notice.id})")
      val message = db.readOnly(commentRepo.get(notice.commentId.get)(_))
      Json.fromJson[MessageDetails](notice.details.payload) match {
        case details: JsSuccess[MessageDetails] =>
          notifyMessageByEmail(notice.userId, message, notice, details.value)
        case error: JsError =>
          log.error("Cannot parse details: " + error)
      }
    case CommentNotification(notice) =>
      val comment = db.readOnly(commentRepo.get(notice.commentId.get)(_))
      commentDetailsFormat.reads(notice.details.payload) match {
        case details: JsSuccess[CommentDetails] =>
          notifyCommentByEmail(notice.userId, comment, details.value)
        case error: JsError =>
          log.error("Cannot parse details: " + error)
      }

  }

  private def notifyCommentByEmail(userId: Id[User], message: Comment, details: CommentDetails) {
    // Disabled for now. Simple to implement, but needs a design / spec.
    /*val author = userRepo.get(details.author.externalId)
    val addrs = emailAddressRepo.getByUser(recipient.id.get)
    for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
      postOffice.sendMail(ElectronicMail(
        senderUserId = author.id,
        from = EmailAddresses.NOTIFICATIONS, fromName = Some("%s %s via Kifi".format(author.firstName, author.lastName)),
        to = addr,
        subject = "%s %s commented on a page you are following".format(author.firstName, author.lastName),
        htmlBody = views.html.email.newComment(author, recipient, details.url, details.title, commentFormatter.toPlainText(details.text)).body,
        category = PostOffice.Categories.COMMENT))
    }*/
  }

  private def notifyMessageByEmail(userId: Id[User], message: Comment, notice: UserNotification, details: MessageDetails) {

    val (recipient, otherParticipants, unreadMessages, addrs, experiments) = db.readOnly { implicit session =>
      val recipient = userRepo.get(userId)
      val addrs = emailAddressRepo.getByUser(recipient.id.get)

      val parent = message.parent.map(commentRepo.get).getOrElse(message)
      val lastReadIdOpt = commentReadRepo.getByUserAndParent(userId, parent.id.get).map(_.lastReadId)

      val entireThread = {
        if (message eq parent) Seq(message)
        else (parent +: commentRepo.getChildren(parent.id.get)).reverse
      }.reverse

      val otherParticipants = {
        val others = ((parent.userId +: commentRecipientRepo.getByComment(parent.id.get).map(_.userId.get)).toSet - userId)
        others.map(userRepo.get).toSeq
      }

      val unreadMessages = (lastReadIdOpt match {
        case Some(lastReadId) =>
          entireThread.filter(c => c.id.get.id > lastReadId.id)
        case None =>
          entireThread
      }) map { msg =>
        val user = userRepo.get(msg.userId)
        (user.firstName + " " + user.lastName, user.externalId.id, commentFormatter.toPlainText(msg.text))
      }
      (recipient, otherParticipants, unreadMessages, addrs, userExperimentRepo.getUserExperiments(userId))
    }

    db.readWrite { implicit session =>
      if (unreadMessages.nonEmpty) {
        log.info(s"Sending email for (${notice.id.get})")
        val authorFirstLast = otherParticipants.map(user => user.firstName + " " + user.lastName).sorted
        val authorFirst = otherParticipants.map(_.firstName).sorted.mkString(", ")
        val formattedTitle = if(details.title.length > 50) details.title.take(50) + "..." else details.title

        val emailBody = views.html.email.unreadMessages(recipient, authorFirstLast, unreadMessages, details).body
        val textBody = views.html.email.unreadMessagesPlain(recipient, authorFirstLast, unreadMessages, details).body

        val p = addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)

        for (addr <- addrs.filter(_.verifiedAt.isDefined).headOption.orElse(addrs.headOption)) {
          postOffice.sendMail(ElectronicMail(
            from = EmailAddresses.NOTIFICATIONS, fromName = Some("KiFi Notifications"),
            to = List(addr),
            subject = s"""New messages on "${formattedTitle}" with $authorFirst""",
            htmlBody = emailBody,
            textBody = Some(textBody),
            category = PostOffice.Categories.COMMENT))
        }
      }
      userNotifyRepo.save(notice.copy(state = UserNotificationStates.DELIVERED))
    }
  }
}

trait UserEmailNotifierPlugin extends SchedulingPlugin {
  def sendEmails(): Unit
}

class UserEmailNotifierPluginImpl @Inject() (
    actorFactory: ActorFactory[UserEmailNotifierActor],
    val schedulingProperties: SchedulingProperties)
  extends UserEmailNotifierPlugin with Logging {

  implicit val actorTimeout = Timeout(5 second)

  private lazy val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting UserEmailNotifierPluginImpl")
    scheduleTask(actorFactory.system, 30 seconds, 2 minutes, actor, SendEmails)
  }

  override def sendEmails() {
    actor ! SendEmails
  }
}

