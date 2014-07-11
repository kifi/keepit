package com.keepit.commanders

import com.keepit.common.queue._
import com.keepit.common.db.Id
import com.keepit.model.{ NotificationCategory, User, SocialUserInfo, Invitation }
import com.keepit.abook.model._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.akka.SafeFuture

import com.google.inject.{ Inject, Singleton, Provider }

import com.kifi.franz.SQSQueue

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Success, Failure, Left, Right }

import akka.actor.Scheduler
import com.keepit.social.SocialNetworkType
import scala.util.Left
import scala.util.Failure
import scala.util.Right
import scala.util.Success
import scala.math
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, EmailAddress }
import scala.collection.mutable
import scala.util.Left
import scala.util.Failure
import scala.util.Right
import scala.util.Success
import com.keepit.shoebox.ShoeboxServiceClient

@Singleton
class LocalRichConnectionCommander @Inject() (
    queue: SQSQueue[RichConnectionUpdateMessage],
    serviceDiscovery: ServiceDiscovery,
    airbrake: AirbrakeNotifier,
    db: Database,
    repo: RichSocialConnectionRepo,
    scheduler: Scheduler,
    eContactRepo: Provider[EContactRepo],
    shoebox: ShoeboxServiceClient) extends RichConnectionCommander with Logging {

  def startUpdateProcessing(): Unit = {
    log.info("RConn: Triggered queued update processing")
    if (serviceDiscovery.isLeader()) {
      log.info("RConn: I'm the leader, let's go")
      processQueueItems()
    } else {
      log.info("RConn: I'm not the leader, nothing to do yet")
      scheduler.scheduleOnce(1 minute) {
        startUpdateProcessing()
      }
    }
  }

  private def processQueueItems(): Unit = {
    log.info("RConn: Fetching one item from the queue")
    val fut = queue.nextWithLock(1 minute)
    fut.onComplete {
      case Success(queueMessageOpt) => {
        log.info("RConn: Queue call returned")
        queueMessageOpt.map { queueMessage =>
          log.info("RConn: Got something")
          try {
            processUpdateImmediate(queueMessage.body).onComplete {
              case Success(_) => {
                queueMessage.consume()
                log.info("RConn: Consumed message")
                processQueueItems()
              }
              case Failure(t) => {
                airbrake.notify("Error processing RichConnectionUpdate from queue", t)
                processQueueItems()
              }
            }
          } catch {
            case t: Throwable =>
              airbrake.notify("Fatal error processing RichConnectionUpdate from queue", t)
              processQueueItems()
          }
        } getOrElse {
          log.info("RConn: Got nothing")
          processQueueItems()
        }
      }
      case Failure(t) => {
        log.info("RConn: Queue call failed")
        airbrake.notify("Failed getting RichConnectionUpdate from queue", t)
        processQueueItems()
      }
    }
  }

  def processUpdate(message: RichConnectionUpdateMessage): Future[Unit] = {
    queue.send(message).map(_ => ())
  }

  def processUpdateImmediate(message: RichConnectionUpdateMessage): Future[Unit] = synchronized {
    log.info(s"[WTI] Processing $message")
    try {
      message match {
        case InternRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) => {
          db.readWrite(attempts = 2) { implicit session =>
            user1.userId.foreach { userId1 => repo.internRichConnection(userId1, user1.id, Left(user2)) }
            user2.userId.foreach { userId2 => repo.internRichConnection(userId2, user2.id, Left(user1)) }
          }
        }
        case RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) => // Ignore

        case RecordInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEmailAddress: Option[EmailAddress], invitationNumber: Int) => {
          db.readWrite { implicit session =>
            val friend = friendSocialId.map(Left(_)).getOrElse(Right(friendEmailAddress.get))
            repo.recordInvitation(userId, friend, invitationNumber)
          }
        }

        case CancelInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEmailAddress: Option[EmailAddress]) => {
          db.readWrite { implicit session =>
            val friend = friendSocialId.map(Left(_)).getOrElse(Right(friendEmailAddress.get))
            repo.cancelInvitation(userId, friend)
          }
        }

        case RecordFriendUserId(networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[EmailAddress], friendUserId: Id[User]) => {
          val friendId = friendSocialId.map(Left(_)).getOrElse(Right(friendEmail.get))
          db.readWrite { implicit session => repo.recordFriendUserId(friendId, friendUserId) }
        }
        case Block(userId: Id[User], networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[EmailAddress]) => {
          val friendId = friendSocialId.map(Left(_)).getOrElse(Right(friendEmail.get))
          db.readWrite { implicit session => repo.block(userId, friendId) }
        }
        case RemoveRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) => {
          db.readWrite { implicit session =>
            user1.userId.foreach { userId1 => repo.removeRichConnection(userId1, user1.id.get, user2.id.get) }
            user2.userId.foreach { userId2 => repo.removeRichConnection(userId2, user2.id.get, user1.id.get) }
          }
        }
        case RemoveKifiConnection(user1: Id[User], user2: Id[User]) => // Ignore
      }
      Future.successful(())
    } catch {
      case t: Throwable => Future.failed(t)
    }
  }

  def processEContact(eContact: EContact): Unit = synchronized {
    db.readWrite { implicit session =>
      repo.internRichConnection(eContact.userId, None, Right(eContact))
      eContact.contactUserId.foreach { contactUserId =>
        repo.recordFriendUserId(Right(eContact.email), contactUserId)
      }
    }
  }

  def validateAllRichConnectionEmails(readOnly: Boolean): Unit = {
    log.info("[RichConnection Email Validation] Starting validation of all Email RichConnections.")
    val invalidConnections = mutable.Map[Id[RichSocialConnection], String]()
    val fixableConnections = mutable.Map[Id[RichSocialConnection], String]()
    val pageSize = 1000
    var lastPageSize = -1
    var nextPage = 0

    do {
      db.readWrite { implicit session =>
        val batch = repo.page(nextPage, pageSize, Set.empty)
        batch.foreach { connection =>
          connection.friendEmailAddress.foreach { emailAddress =>
            EmailAddress.validate(emailAddress.address) match {
              case Failure(invalidEmail) => {
                log.error(s"[RichConnection Email Validation] Found invalid email rich connection: ${connection}")
                invalidConnections += (connection.id.get -> emailAddress.address)
                if (!readOnly) { repo.save(connection.copy(state = RichSocialConnectionStates.INACTIVE)) }
              }
              case Success(validEmail) => {
                if (validEmail != emailAddress) {
                  log.warn(s"[RichConnection Email Validation] Found fixable email rich connection: ${connection}")
                  fixableConnections += (connection.id.get -> emailAddress.address)
                  if (!readOnly) { repo.save(connection.copy(friendEmailAddress = Some(validEmail))) }
                }
              }
            }
          }
          lastPageSize = batch.length
          nextPage += 1
        }
      }
    } while (lastPageSize == pageSize)

    log.info("[RichConnection Email Validation] Done with RichConnection Email validation.")

    val title = s"RichConnection Email Validation Report: ReadOnly Mode = $readOnly. Invalid Contacts: ${invalidConnections.size}. Fixable Contacts: ${fixableConnections.size}"
    val msg = s"Invalid Email RichConnections: \n\n ${invalidConnections.mkString("\n")} \n\n Fixable Contacts: \n\n ${fixableConnections.mkString("\n")}"
    shoebox.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = title, htmlBody = msg.replaceAll("\n", "\n<br>"), category = NotificationCategory.System.ADMIN
    ))
  }
}
