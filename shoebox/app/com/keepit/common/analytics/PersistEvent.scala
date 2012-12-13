package com.keepit.common.analytics

import akka.dispatch.Await
import akka.actor.ActorSystem
import com.keepit.common.db.Id
import play.api.Plugin
import akka.actor.Cancellable
import com.google.inject.Inject
import akka.actor.Actor
import com.keepit.common.logging.Logging
import com.keepit.search.graph.URIGraph
import akka.util.Timeout
import akka.actor.Props
import com.keepit.model.User
import akka.util.duration._
import play.api.Play.current
import com.keepit.common.healthcheck._
import com.keepit.inject._
import com.keepit.common.time._
import org.joda.time._

case object Load
case class Update(userId: Id[User])
case class Persist(event: Event)
case class PersistMany(events: Seq[Event])

private[analytics] class PersistEventActor extends Actor with Logging {

  def receive() = {
    case Persist(event) =>
      val diff = Seconds.secondsBetween(event.createdAt, currentDateTime).getSeconds
      if(diff > 60) {
        val ex = new Exception("Event log is backing up. Event is %s seconds old".format(diff))
        inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        // To keep the event log from backing too far up, ignore very old events.
        // If we get this, use parallel actors.
      }
      else {
        try { event.persistToS3 } catch { case ex: Throwable =>
          val ex = new Exception("Could not persist event to S3")
          inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        }
        try { event.persistToMongo } catch { case ex: Throwable =>
          val ex = new Exception("Could not persist event to Mongo")
          inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        }
      }
    case PersistMany(events) =>
      events foreach ( self ! _ )
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait PersistEventPlugin extends Plugin {
  def persist(event: Event): Unit
  def persist(events: Seq[Event]): Unit
}


class PersistEventPluginImpl @Inject() (system: ActorSystem) extends PersistEventPlugin with Logging {

  private val actor = system.actorOf(Props { new PersistEventActor })

  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting PersistEventImpl")
  }
  override def onStop(): Unit = {
    log.info("stopping PersistEventImpl")
  }

  def persist(event: Event): Unit = actor ! Persist(event)
  def persist(events: Seq[Event]): Unit = actor ! PersistMany(events)
}

class FakePersistEventPluginImpl(system: ActorSystem) extends PersistEventPlugin with Logging {
  def persist(event: Event): Unit = {
    log.info("Fake persisting event %s".format(event.externalId))
  }
  def persist(events: Seq[Event]): Unit = {
    log.info("Fake persisting events %s".format(events map (_.externalId) mkString(",")))
  }
}
