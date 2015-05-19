package com.keepit.normalizer

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.actor.ActorInstance
import akka.util.Timeout
import scala.concurrent.duration._
import play.api.{ Play, Plugin }
import com.keepit.model.{ NormalizedURIRepo }
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.queue.{ NormalizationUpdateTask }
import com.keepit.common.aws.AwsConfig
import com.kifi.franz.SQSQueue
import scala.util.{ Failure, Success }

case object Consume

class NormalizationWorker @Inject() (
    db: Database,
    nuriRepo: NormalizedURIRepo,
    airbrake: AirbrakeNotifier,
    updateQ: SQSQueue[NormalizationUpdateTask],
    normalizationService: NormalizationService,
    val scheduling: SchedulingProperties,
    awsConfig: AwsConfig) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case Consume => consume()
    case m => throw new UnsupportedActorMessage(m)
  }

  def consume() = {
    updateQ.nextBatchWithLock(3, 2 minutes) onComplete {
      case Failure(t) =>
        airbrake.notify(s"Caught exception $t while consuming messages from $updateQ", t)
      case Success(messages) =>
        log.debug(s"[consume] messages:(len=${messages.length})[${messages.mkString(",")}]")
        for (m <- messages) {
          log.debug(s"[consume] received msg $m")
          m.consume { task =>
            val nuri = db.readOnlyMaster { implicit ro =>
              nuriRepo.get(task.uriId)
            }
            val ref = NormalizationReference(nuri, task.isNew)
            log.debug(s"[consume] nuri=$nuri ref=$ref candidates=${task.candidates}")
            for (nuriOpt <- normalizationService.update(ref, task.candidates)) {
              // sends out-of-band requests to scraper
              log.debug(s"[consume] normalizationService.update result: $nuriOpt")
            }
          }
        }
    }
  }

}

trait NormalizationUpdaterPlugin extends Plugin {
}

class NormalizationUpdaterPluginImpl @Inject() (
    actor: ActorInstance[NormalizationWorker],
    val scheduling: SchedulingProperties) extends NormalizationUpdaterPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("[onStart] starting NormalizationUpdater ...")
    scheduleTaskOnAllMachines(actor.system, 60 seconds, 5 seconds, actor.ref, Consume)
  }

}
