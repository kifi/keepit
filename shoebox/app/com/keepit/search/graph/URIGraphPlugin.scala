package com.keepit.search.graph

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.actor.ActorFactory
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._

case object Update

private[graph] class URIGraphActor @Inject() (
    uriGraph: URIGraph)
  extends FortyTwoActor with Logging {

  def receive() = {
    case Update => try {
        sender ! uriGraph.update()
      } catch {
        case e: Exception =>
          inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
              errorMessage = Some("Error updating uri graph")))
          sender ! -1
      }
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait URIGraphPlugin extends SchedulingPlugin {
  def update(): Future[Int]
  def reindex()
}

class URIGraphPluginImpl @Inject() (
    actorFactory: ActorFactory[URIGraphActor],
    uriGraph: URIGraph)
  extends URIGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 30 seconds, 1 minute, actor, Update)
    log.info("starting URIGraphPluginImpl")
  }
  override def onStop() {
    log.info("stopping URIGraphPluginImpl")
    cancelTasks()
    uriGraph.close()
  }

  override def update(): Future[Int] = actor.ask(Update)(1 minutes).mapTo[Int]

  override def reindex() {
    uriGraph.sequenceNumber = SequenceNumber.ZERO
    actor ! Update
  }
}
