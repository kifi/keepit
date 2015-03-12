package com.keepit.scraper.actor

import akka.actor._
import akka.pattern.pipe
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor, SafeFuture }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.scraper.ScrapeWorker
import com.keepit.scraper.actor.InternalMessages.{ ScrapeAgentTimeout, JobAborted }

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
      parent ! WorkerAvail(self)
    case job: ScrapeJob =>
      log.info(s"[ScrapeAgent($name).idle] <ScrapeJob> got assigned $job")
      context.become(busy(job))
      context.setReceiveTimeout(60 seconds)
      worker.safeProcess(job.s.uri, job.s.info, job.s.pageInfo, job.s.proxyOpt).map { res =>
        val done = JobDone(self, job, res)
        parent ! done
        self ! done
      }.recover {
        case _ =>
          val done = JobDone(self, job, None)
          parent ! done
          self ! done
      }
    case ReceiveTimeout =>
      log.info(s"[ScrapeAgent($name).idle] has been idle for a while")
    case m => throw new UnsupportedActorMessage(m)
  }

  def busy(s: ScrapeJob): Receive = {
    case d: JobDone =>
      log.info(s"[ScrapeAgent($name).busy] <JobDone> $d")
      context.become(idle) // unbecome shouldn't be necessary
      parent ! WorkerAvail(self)
    case ReceiveTimeout =>
      log.error(s"[ScrapeAgent($name).busy] ReceiveTimeout exception when busy")
      context.become(idle)
      parent ! ScrapeAgentTimeout(self)
    case JobAvail | ScrapeJob =>
      log.warn(s"[ScrapeAgent($name).busy], not supposed to receive JobAvail or ScrapeJob message")

    case m => throw new UnsupportedActorMessage(m)
  }

  def receive = idle

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.error(s"[ScrapeAgent($name).preRestart] reason=$reason message=$message")
    message foreach { m =>
      m match {
        case job: ScrapeJob =>
          log.warn(s"[ScrapeAgent($name).preRestart] died while processing job $job; notify parent")
          parent ! JobAborted(self, job)
        case _ =>
      }
    }
    super.preRestart(reason, message)
  }
}