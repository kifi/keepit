package com.keepit.search.graph

import scala.collection.mutable.MutableList
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.Play.current
import play.api.Plugin
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

case object Load
case class Update(userId: Id[User])

private[graph] class URIGraphActor(uriGraph: URIGraph) extends Actor with Logging {

  def receive() = {
    case Load => try {
        sender ! uriGraph.load()
      } catch {
        case e: Exception =>
          inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
              errorMessage = Some("Error loading uri graph")))
          sender ! -1
      }
    case Update(u) => try {
        sender ! uriGraph.update(u)
      } catch {
        case e: Exception =>
          inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
              errorMessage = Some("Error updating uri graph with %s".format(u))))
          sender ! -1
      }
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait URIGraphPlugin extends Plugin {
  def load(): Int
  def update(userId: Id[User]): Int
}

class URIGraphPluginImpl @Inject() (system: ActorSystem, uriGraph: URIGraph) extends URIGraphPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new URIGraphActor(uriGraph) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting URIGraphPluginImpl")
  }
  override def onStop(): Unit = {
    log.info("stopping URIGrpahPluginImpl")
    _cancellables.map(_.cancel)
    uriGraph.close()
  }

  override def load(): Int = {
    val future = actor.ask(Load)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def update(userId: Id[User]) = {
    val future = actor.ask(Update(userId))(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }
}
