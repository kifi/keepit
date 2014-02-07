package com.keepit.normalizer

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.common.queue.{NormalizationUpdateTaskQ, SimpleQueueService}
import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import com.keepit.common.actor.ActorInstance
import akka.util.Timeout
import scala.concurrent.duration._
import play.api.Plugin
import com.keepit.model.{NormalizedURIRepo, NormalizationUpdateTask}
import play.api.libs.json._
import com.keepit.common.db.slick.Database
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case object Consume

class NormalizationWorker @Inject()(
  db:Database,
  nuriRepo:NormalizedURIRepo,
  airbrake:AirbrakeNotifier,
  sqs:SimpleQueueService,
  q:NormalizationUpdateTaskQ,
  normalizationService:NormalizationService,
  val scheduling: SchedulingProperties
) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case Consume => consume()
    case m => throw new UnsupportedActorMessage(m)
  }

  def consume() = {
    val messages = q.receive()
    log.info(s"[consume] messages:(len=${messages.length})[${messages.mkString(",")}]")
    for (m <- messages) {
      log.info(s"[consume] received msg $m")
      val taskOpt = Json.fromJson[NormalizationUpdateTask](Json.parse(m.body)).asOpt
      log.info(s"[consume] task=$taskOpt")

      taskOpt map { task =>
        val nuri = db.readOnly { implicit ro =>
          nuriRepo.get(task.uriId)
        }
        val ref = NormalizationReference(nuri, task.isNew)
//        val candidates = task.candidates.map { c => TNormalizationCandidate.toNormalizedCandidate(c) }
        log.info(s"[consume] nuri=$nuri ref=$ref candidates=${task.candidates}")
        for (nuriOpt <- normalizationService.update(ref, task.candidates:_*)) { // sends out-of-band requests to scraper
          log.info(s"[consume] normalizationService.update result: $nuriOpt")
        }
      }

      log.info(s"[consume] done with $m - DELETE")
      q.delete(m.receiptHandle)
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