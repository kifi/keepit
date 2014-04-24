package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import play.api.mvc.Action
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorInstance
import com.kifi.franz.{QueueName, SimpleSQSClient}
import com.amazonaws.regions.Regions
import com.keepit.graph.manager.{UserGraphUpdate, GraphUpdate}
import scala.concurrent.Await
import com.amazonaws.auth.BasicAWSCredentials
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.db.slick.Database
import com.keepit.common.db.SequenceNumber
import com.keepit.model.{UserRepo, User}

private sealed trait UpdateMessage
private case class UserUpdate(seq: SequenceNumber[User], queueRef: QueueName) extends UpdateMessage

private class GraphUpdateActor @Inject() (
  airbrake: AirbrakeNotifier,
  basicAWSCreds:BasicAWSCredentials,
  userRepo: UserRepo,
  db: Database
)  extends FortyTwoActor(airbrake) with Logging {
  private val fetchSize = 100
  private lazy val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)

  def createQueue(queueRef: QueueName) = {
    client.formatted[GraphUpdate](queueRef, true)
  }

  def receive = {
    case UserUpdate(seq, queueRef) =>
      val queue = createQueue(queueRef)
      val updates = db.readOnly { implicit session =>
        userRepo.getUsersSince(seq, fetchSize)
      }.map { user =>
        UserGraphUpdate(user.id.get, user.seq)
      }

      updates.map(queue.send)
  }
}

class ShoeboxGraphController @Inject() (
  actor: ActorInstance[GraphUpdateActor]
) extends ShoeboxServiceController with Logging {
  def sendUserUpdates() = Action(parse.tolerantJson) { request =>
    val seq = SequenceNumber[User]((request.body \ "seq").as[Long])
    val queueRef = QueueName("") // todo fix!
    actor.ref ! UserUpdate(seq, queueRef)
    Status(202)("0")
  }
}
