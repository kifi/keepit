package com.keepit.normalizer

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.common.queue.SimpleQueueService
import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.keepit.common.actor.ActorInstance
import akka.util.Timeout
import scala.concurrent.duration._
import play.api.{Play, Plugin}
import com.keepit.model.{NormalizedURIRepo}
import play.api.libs.json._
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.queue.{NormalizationUpdateTask, NormalizationUpdateJobQueue}
import com.keepit.common.aws.AwsConfig

case object Consume

class NormalizationWorker @Inject()(
  db:Database,
  nuriRepo:NormalizedURIRepo,
  airbrake:AirbrakeNotifier,
  sqs:SimpleQueueService,
  q:NormalizationUpdateJobQueue,
  normalizationService:NormalizationService,
  val scheduling: SchedulingProperties,
  awsConfig: AwsConfig
) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case Consume => consume()
    case m => throw new UnsupportedActorMessage(m)
  }

  val sqsEnable:Boolean = awsConfig.sqsEnabled // todo(ray):removeme
  def consume() = {
    if (sqsEnable) {
      try {
        val messages = q.receive()
        log.info(s"[consume] messages:(len=${messages.length})[${messages.mkString(",")}]")
        val receiveTS = System.currentTimeMillis()
        for (m <- messages) {
          try {
            log.info(s"[consume] received msg $m")
            val taskOpt = Json.fromJson[NormalizationUpdateTask](Json.parse(m.body)).asOpt

            taskOpt map { task =>
              val nuri = db.readOnly { implicit ro =>
                nuriRepo.get(task.uriId)
              }
              val ref = NormalizationReference(nuri, task.isNew)
              log.info(s"[consume] nuri=$nuri ref=$ref candidates=${task.candidates}")
              for (nuriOpt <- normalizationService.update(ref, task.candidates:_*)) { // sends out-of-band requests to scraper
                log.info(s"[consume] normalizationService.update result: $nuriOpt")
              }
            }
          } catch {
            case t:Throwable => airbrake.notify(s"Caught exception $t while consuming message $m - DELETE")
          } finally {
            log.info(s"[consume] done with $m - DELETE") // trial phase
            q.delete(m.receiptHandle) // todo(ray): add err/retry count
          }
        }
      } catch {
        case t:Throwable => airbrake.notify(s"Caught exception $t while consuming messages from $q",t)
      }
    }
  }

}

trait NormalizationUpdaterPlugin extends Plugin {
}

class NormalizationUpdaterPluginImpl @Inject()(
  actor:ActorInstance[NormalizationWorker],
  val scheduling:SchedulingProperties
) extends NormalizationUpdaterPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("[onStart] starting NormalizationUpdater ...")
    scheduleTaskOnAllMachines(actor.system, 10 seconds, 10 seconds, actor.ref, Consume)
  }

}