package com.keepit.search.comment

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorInstance
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.index.BackUp

case object Update

private[comment] class CommentIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    commentIndexer: CommentIndexer)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Update => try {
        sender ! commentIndexer.update()
      } catch {
        case e: Exception =>
          airbrake.notify(AirbrakeError(exception = e, message = Some("Error updating comment index")))
          sender ! -1
      }
    case BackUp => commentIndexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait CommentIndexerPlugin extends SchedulingPlugin {
  def update(): Future[Int]
  def reindex()
}

class CommentIndexerPluginImpl @Inject() (
    actor: ActorInstance[CommentIndexerActor],
    commentIndexer: CommentIndexer,
    serviceDiscovery: ServiceDiscovery)
  extends CommentIndexerPlugin with Logging {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actor.system, 30 seconds, 1 minute, actor.ref, Update)
    log.info("starting CommentIndexerPluginImpl")
  }
  override def onStop() {
    log.info("stopping CommentIndexerPluginImpl")
    cancelTasks()
    commentIndexer.close()
  }

  override def update(): Future[Int] = actor.ref.ask(Update)(1 minutes).mapTo[Int]

  override def reindex() {
    commentIndexer.reindex()
    actor.ref ! Update
  }
}
