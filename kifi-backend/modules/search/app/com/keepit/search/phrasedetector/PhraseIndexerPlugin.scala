package com.keepit.search.phrasedetector

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.SchedulingPlugin
import scala.concurrent.Await
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeError
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.service.ServiceStatus

case object Index
case object BackUp

private[phrasedetector] class PhraseIndexerActor @Inject() (
  airbrake: AirbrakeNotifier,
  phraseIndexer: PhraseIndexer)
extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Index => try {
      val phrasesIndexed = phraseIndexer.update()
      if (phrasesIndexed >= phraseIndexer.getCommitBatchSize) {
        self.forward(Index)
      }
      sender ! phrasesIndexed
    } catch {
      case e: Exception =>
        airbrake.notify(AirbrakeError(exception = e, message = Some("Error indexing Phrases")))
        sender ! -1
    }
    case BackUp => phraseIndexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait PhraseIndexerPlugin extends SchedulingPlugin {
  def index(): Int
  def reindex()
}

class PhraseIndexerPluginImpl @Inject() (
  actor: ActorInstance[PhraseIndexerActor],
  phraseIndexer: PhraseIndexer,
  serviceDiscovery: ServiceDiscovery)
  extends PhraseIndexerPlugin with Logging {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting PhraseIndexerPluginImpl")
    scheduleTask(actor.system, 30 seconds, 1 minutes, actor.ref, Index)
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTask(actor.system, 20 minutes, 4 hours, actor.ref, BackUp)
    }
  }
  override def onStop() {
    log.info("stopping PhraseIndexerPluginImpl")
    phraseIndexer.close()
  }

  override def index(): Int = {
    val future = actor.ref.ask(Index)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def reindex() {
    phraseIndexer.reindex()
    actor.ref ! Index
  }
}
