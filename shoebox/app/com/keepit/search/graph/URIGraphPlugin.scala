package com.keepit.search.graph

import scala.collection.mutable.MutableList
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.Play.current
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits._
import akka.pattern.ask
import play.api.libs.concurrent._
import org.joda.time.DateTime
import com.google.inject.Inject
import com.google.inject.Provider
import com.keepit.inject._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin

case object Load
case object Update

private[graph] class URIGraphActor(uriGraph: URIGraph) extends FortyTwoActor with Logging {

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
}

class URIGraphPluginImpl @Inject() (system: ActorSystem, uriGraph: URIGraph) extends URIGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new URIGraphActor(uriGraph) })

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting URIGraphPluginImpl")
  }
  override def onStop() {
    log.info("stopping URIGrpahPluginImpl")
    uriGraph.close()
  }

  override def update(): Future[Int] = actor.ask(Update)(1 minutes).mapTo[Int]
}
