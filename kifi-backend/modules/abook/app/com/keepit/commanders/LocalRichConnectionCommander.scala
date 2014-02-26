package com.keepit.commanders

import com.keepit.common.queue.{
  RichConnectionUpdateMessage,
  CreateRichConnection,
  RecordKifiConnection,
  RecordInvitation,
  RecordFriendUserId,
  Block
}
import com.keepit.common.db.Id
import com.keepit.model.{User, SocialUserInfo, Invitation}
import com.keepit.abook.model.RichSocialConnectionRepo
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.slick.Database

import com.google.inject.{Inject, Singleton}

import com.kifi.franz.FormattedSQSQueue

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure, Left, Right}


@Singleton
class LocalRichConnectionCommander @Inject() (
    queue: FormattedSQSQueue[RichConnectionUpdateMessage],
    serviceDiscovery: ServiceDiscovery,
    airbrake: AirbrakeNotifier,
    db: Database,
    repo: RichSocialConnectionRepo
  ) extends RichConnectionCommander {

  if (serviceDiscovery.isLeader()) {
    processQueueItems()
  }

  private def processQueueItems(): Unit = {
    val fut = queue.nextWithLock(1 minute)
    fut.onComplete{
      case Success(queueMessage) => {
        try {
          processUpdateImmediate(queueMessage.body).onComplete{
            case Success(_) => {
              queueMessage.consume()
              processQueueItems()
            }
            case Failure(t) => {
              airbrake.notify("Error processing RichConnectionUpdate from queue")
              processQueueItems()
            }
          }
        } catch {
          case t: Throwable => airbrake.notify("Fatal error processing RichConnectionUpdate from queue", t)
          processQueueItems()
        }
      }
      case Failure(t) => {
        airbrake.notify("Failed getting RichConnectionUpdate from queue", t)
        processQueueItems()
      }
    }
  }

  def processUpdate(message: RichConnectionUpdateMessage): Future[Unit] = {
    queue.send(message).map(_ => ())
  }

  def processUpdateImmediate(message: RichConnectionUpdateMessage): Future[Unit] = synchronized {
    //ZZZ actually apply updates to DB
    // def recordInvitation(userId: Id[User], invitation: Id[Invitation], friend: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit
    // def recordFriendUserId(friendId: Either[Id[SocialUserInfo], String], friendUserId: Id[User])(implicit session: RWSession): Unit
    // def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit


    try {
      message match {
        case CreateRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: SocialUserInfo) => {
          db.readWrite { implicit session => repo.internRichConnection(userId, Some(userSocialId), Left(friend)) }
        }
        case RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) => {
          db.readWrite { implicit session =>  repo.recordKifiConnection(firstUserId, secondUserId) }
        }
        // case RecordInvitation(userId: Id[User], invitation: Id[Invitation], networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) => {
        //   val friend = Left(friendSocialId) getOrElse
        //   db.readWrite { implicit session => repo.recordInvitation(userId, invitation, friend: Either[Id[SocialUserInfo], String])
        // }
        // case RecordFriendUserId(networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String], friendUserId: Id[User]) => {
        //   db.readWrite { implicit session =>
        // }
        // case Block(userId: Id[User], networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) => {
        //   db.readWrite { implicit session =>
        // }
      }
      Future.successful(())
    } catch {
      case t: Throwable => Future.failed(t)
    }
  }

}
