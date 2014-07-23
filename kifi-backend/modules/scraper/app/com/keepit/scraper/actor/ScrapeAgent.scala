package com.keepit.scraper.actor

import akka.actor._
import akka.pattern.pipe
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, SafeFuture }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.scraper.ScrapeWorker

import scala.concurrent.duration._

class ScrapeAgent @Inject() (
    airbrake: AirbrakeNotifier,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  import InternalMessages.{ WorkerCreated, WorkerAvail, WorkerBusy, JobAvail, ScrapeJob, JobDone }

  val name = self.path.name
  log.info(s"[ScrapeAgent($name)] created! parent=${context.parent} props=${context.props} context=${context}")

  implicit val timeout = Timeout(10 seconds)
  implicit val fj = ExecutionContext.fj

  val parent = context.parent
  parent ! WorkerCreated(self)
  parent ! WorkerAvail(self)

  def idle: Receive = {
    case JobAvail =>
      log.info(s"[ScrapeAgent($name).idle] <JobAvail> responds with <WorkerAvail>")
      parent ! WorkerAvail(self)
    case job: ScrapeJob =>
      log.info(s"[ScrapeAgent($name).idle] <ScrapeJob> got assigned $job")
      context.become(busy(job))
      SafeFuture {
        JobDone(job, worker.safeProcessURI(job.s.uri, job.s.info, job.s.pageInfo, job.s.proxyOpt)) // blocking call (for now)
      }.pipeTo(self)
    case m =>
      log.info(s"[ScrapeAgent($name).idle] ignore event $m")
  }

  def busy(s: ScrapeJob): Receive = {
    case JobAvail =>
      log.info(s"[ScrapeAgent($name).busy] ignore event <JobAvail>")
    case d: JobDone =>
      log.info(s"[ScrapeAgent($name).busy] <JobDone> $d")
      context.become(idle) // unbecome shouldn't be necessary
      parent ! WorkerAvail(self)
    case job: ScrapeJob =>
      log.warn(s"[ScrapeAgent($name).busy] reject <ScrapeJob> assignment: $job")
      parent ! WorkerBusy(self, job)
    case m =>
      log.info(s"[ScrapeAgent($name).busy] ignore event $m")
  }

  def receive = idle

}
