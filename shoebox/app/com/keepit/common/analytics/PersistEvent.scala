package com.keepit.common.analytics

import scala.concurrent.Await
import akka.actor.ActorSystem
import com.keepit.common.db.Id
import play.api.Plugin
import akka.actor.Cancellable
import org.joda.time._

import com.google.inject.Inject

import com.keepit.common.actor.ActorFactory
import com.keepit.common.db.Id
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model.User
import play.api.libs.concurrent.Execution.Implicits._

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props

import play.api.Play.current
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.SchedulingPlugin

case object Load
case class Update(userId: Id[User])
case class Persist(event: Event, queueTime: DateTime)
case class PersistMany(events: Seq[Event], queueTime: DateTime)

private[analytics] class PersistEventActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case Persist(event, queueTime) =>
      val diff = Seconds.secondsBetween(queueTime, currentDateTime).getSeconds
      if(diff > 120) {
        val ex = new Exception("Event log is backing up. Event was queued %s seconds ago".format(diff))
        inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        // To keep the event log from backing too far up, ignore very old events.
        // If we get this, use parallel actors.
      }
      else {
        try { event.persistToS3() } catch { case ex: Throwable =>
          inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        }
        try { event.persistToMongo() } catch { case ex: Throwable =>
          inject[HealthcheckPlugin].addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        }
      }
    case PersistMany(events, queueTime) =>
      events foreach ( self ! Persist(_, queueTime) )
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait PersistEventPlugin extends SchedulingPlugin {
  def persist(event: Event): Unit
  def persist(events: Seq[Event]): Unit
}

class PersistEventPluginImpl @Inject() (
    actorFactory: ActorFactory[PersistEventActor])
    extends PersistEventPlugin with Logging {

  private val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting PersistEventImpl")
  }
  override def onStop() {
    log.info("stopping PersistEventImpl")
  }

  def persist(event: Event): Unit = actor ! Persist(event, currentDateTime)
  def persist(events: Seq[Event]): Unit = actor ! PersistMany(events, currentDateTime)
}

class FakePersistEventPluginImpl @Inject() (system: ActorSystem) extends PersistEventPlugin with Logging {

  def persist(event: Event): Unit = {
    log.info("Fake persisting event %s".format(event.externalId))
  }
  def persist(events: Seq[Event]): Unit = {
    log.info("Fake persisting events %s".format(events map (_.externalId) mkString(",")))
  }
}
