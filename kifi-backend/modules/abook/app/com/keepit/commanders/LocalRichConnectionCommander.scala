package com.keepit.commanders

import com.keepit.common.queue.{
  RichConnectionUpdateMessage,
  InternRichConnection,
  RecordKifiConnection,
  RecordInvitation,
  RecordFriendUserId,
  Block,
  RemoveRichConnection,
  RemoveKifiConnection
}
import com.keepit.common.db.Id
import com.keepit.model.{EContact, User, SocialUserInfo, Invitation}
import com.keepit.abook.model.RichSocialConnectionRepo
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging

import com.google.inject.{Inject, Singleton}

import com.kifi.franz.FormattedSQSQueue

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Left, Right}

import akka.actor.Scheduler
import com.keepit.social.SocialNetworkType
import com.keepit.abook.EContactRepo


@Singleton
class LocalRichConnectionCommander @Inject() (
    queue: FormattedSQSQueue[RichConnectionUpdateMessage],
    serviceDiscovery: ServiceDiscovery,
    airbrake: AirbrakeNotifier,
    db: Database,
    repo: RichSocialConnectionRepo,
    eContactRepo: EContactRepo,
    scheduler: Scheduler
  ) extends RichConnectionCommander with Logging {


  def startUpdateProcessing(): Unit = {
    log.info("RConn: Triggered queued update processing")
    if (serviceDiscovery.isLeader()) {
      log.info("RConn: I'm the leader, let's go")
      processQueueItems()
    } else {
      log.info("RConn: I'm not the leader, nothing to do yet")
      scheduler.scheduleOnce(1 minute){
        startUpdateProcessing()
      }
    }
  }

  private def processQueueItems(): Unit = {
    log.info("RConn: Fetching one item from the queue")
    val fut = queue.nextWithLock(1 minute)
    fut.onComplete{
      case Success(queueMessageOpt) => {
        log.info("RConn: Queue call returned")
        queueMessageOpt.map { queueMessage =>
          log.info("RConn: Got something")
          try {
            processUpdateImmediate(queueMessage.body).onComplete{
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
            case t: Throwable => airbrake.notify("Fatal error processing RichConnectionUpdate from queue", t)
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
    try {
      message match {
        case InternRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: SocialUserInfo) => {
          db.readWrite { implicit session => repo.internRichConnection(userId, Some(userSocialId), Left(friend)) }
        }
        case RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) => {
          db.readWrite { implicit session =>  repo.recordKifiConnection(firstUserId, secondUserId) }
        }
        case RecordInvitation(userId: Id[User], invitation: Id[Invitation], friendSocialId: Option[Id[SocialUserInfo]], friendEContact: Option[Id[EContact]]) => {
          db.readWrite { implicit session =>
            val friend = friendSocialId.map(Left(_)).getOrElse(Right(eContactRepo.get(friendEContact.get).email))
            repo.recordInvitation(userId, invitation, friend) }
        }
        case RecordFriendUserId(networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String], friendUserId: Id[User]) => {
          val friendId = friendSocialId.map(Left(_)).getOrElse(Right(friendEmail.get))
          db.readWrite { implicit session => repo.recordFriendUserId(friendId, friendUserId) }
        }
        case Block(userId: Id[User], networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) => {
          val friendId = friendSocialId.map(Left(_)).getOrElse(Right(friendEmail.get))
          db.readWrite { implicit session => repo.block(userId, friendId) }
        }
        case RemoveRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: Id[SocialUserInfo]) => {
          db.readWrite { implicit session => repo.removeRichConnection(userId, userSocialId, friend) }
        }
        case RemoveKifiConnection(user1: Id[User], user2: Id[User]) => {
          db.readWrite { implicit session => repo.removeKifiConnection(user1, user2) }
        }
      }
      Future.successful(())
    } catch {
      case t: Throwable => Future.failed(t)
    }
  }
}
