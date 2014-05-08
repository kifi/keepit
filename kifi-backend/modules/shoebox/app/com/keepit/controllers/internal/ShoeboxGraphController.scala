package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import play.api.mvc.Action
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorInstance
import com.kifi.franz.{SQSQueue, QueueName, SimpleSQSClient}
import com.amazonaws.regions.Regions
import com.keepit.graph.manager._
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.db.slick.Database
import com.keepit.common.db.SequenceNumber
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.model.SocialConnection
import scala.concurrent.Future


private sealed trait UpdateMessage
private case class UserUpdate(seq: SequenceNumber[User], queueRef: QueueName) extends UpdateMessage
private case class SocialConnectionUpdate(seq: SequenceNumber[SocialConnection], queueRef: QueueName) extends UpdateMessage
private case class SocialUserInfoUpdate(seq: SequenceNumber[SocialUserInfo], queueRef: QueueName) extends UpdateMessage
private case class UserConnectionUpdate(seq: SequenceNumber[UserConnection], queueRef: QueueName) extends UpdateMessage
private case class KeepUpdate(seq: SequenceNumber[Keep], queueRef: QueueName) extends UpdateMessage


private class GraphUpdateActor @Inject() (
  airbrake: AirbrakeNotifier,
  basicAWSCreds: BasicAWSCredentials,
  userRepo: UserRepo,
  socialConnectionRepo: SocialConnectionRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  userConnectionRepo: UserConnectionRepo,
  keepRepo: KeepRepo,
  db: Database
)  extends FortyTwoActor(airbrake) with Logging {
  private val fetchSize = 1000
  private lazy val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  def createQueue(queueRef: QueueName) = {
    client.formatted[GraphUpdate](queueRef, true)
  }

  private def sendSequentially[T](queue: SQSQueue[T], messages: Seq[T]): Future[Unit] = {
    queue.send(messages.head).map { _ => sendSequentially(queue, messages.tail) }
  }

  def receive = {
    case UserUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        userRepo.getBySequenceNumber(seq, fetchSize)
      }.map { user =>
        UserGraphUpdate(user.id.get, user.seq)
      }

      sendSequentially(queue, updates)

    case SocialConnectionUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        socialConnectionRepo.getConnAndNetworkBySeqNumber(seq, fetchSize)
      }.map { case (su1, su2, state, seq, networkType) =>
        SocialConnectionGraphUpdate(su1, su2, networkType, state, seq)
      }

      sendSequentially(queue, updates)

    case SocialUserInfoUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        socialUserInfoRepo.getBySequenceNumber(seq, fetchSize)
      }.map { su =>
        SocialUserInfoGraphUpdate(su.id.get, su.networkType, su.userId, su.seq)
      }

      sendSequentially(queue, updates)

    case UserConnectionUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        userConnectionRepo.getBySequenceNumber(seq, fetchSize)
      }.map { uc =>
        UserConnectionGraphUpdate(uc.user1, uc.user2, uc.state, uc.seq)
      }

      sendSequentially(queue, updates)

    case KeepUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        keepRepo.getBySequenceNumber(seq, fetchSize)
      }.map { keep =>
        KeepGraphUpdate(keep.id.get, keep.userId, keep.uriId, keep.state, keep.seq)
      }

      sendSequentially(queue, updates)
  }
}

class ShoeboxGraphController @Inject() (
  actor: ActorInstance[GraphUpdateActor]
) extends ShoeboxServiceController with Logging {

  def sendUserUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[User]((request.body \ "seq").as[Long])
    val queueRef = QueueName((request.body \ "queue").as[String])
    actor.ref ! UserUpdate(seq, queueRef)
    Status(202)("0")
  }

  def sendSocialConnectionUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[SocialConnection]((request.body \ "seq").as[Long])
    val queueRef = QueueName((request.body \ "queue").as[String])
    actor.ref ! SocialConnectionUpdate(seq, queueRef)
    Status(202)("0")
  }

  def sendSocialUserInfoUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[SocialUserInfo]((request.body \ "seq").as[Long])
    val queueRef = QueueName((request.body \ "queue").as[String])
    actor.ref ! SocialUserInfoUpdate(seq, queueRef)
    Status(202)("0")
  }

  def sendUserConnectionUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[UserConnection]((request.body \ "seq").as[Long])
    val queueRef = QueueName((request.body \ "queue").as[String])
    actor.ref ! UserConnectionUpdate(seq, queueRef)
    Status(202)("0")
  }

  def sendKeepUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[Keep]((request.body \ "seq").as[Long])
    val queueRef = QueueName((request.body \ "queue").as[String])
    actor.ref ! KeepUpdate(seq, queueRef)
    Status(202)("0")
  }
}
