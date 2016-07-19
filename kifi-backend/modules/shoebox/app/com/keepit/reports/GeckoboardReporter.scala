package com.keepit.reports

import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }
import com.keepit.common.geckoboard._
import akka.actor._
import akka.util.Timeout
import com.keepit.model.KeepRepo
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.time._
import com.keepit.inject._
import play.api.Plugin
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import us.theatr.akka.quartz.QuartzActor

private[reports] class GeckoboardReporterActor @Inject() (
  airbrake: AirbrakeNotifier,
  geckoboardPublisher: GeckoboardPublisher)
    extends FortyTwoActor(airbrake) with Logging {
  def receive() = {
    case widget: GeckoboardWidget[_] => geckoboardPublisher.publish(widget)
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait GeckoboardReporterPlugin extends SchedulerPlugin {
  def refreshAll(): Unit
}

class GeckoboardReporterPluginImpl @Inject() (
  actor: ActorInstance[GeckoboardReporterActor],
  quartz: ActorInstance[QuartzActor],
  val scheduling: SchedulingProperties)
    extends GeckoboardReporterPlugin with Logging {

  implicit val actorTimeout = Timeout(60 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  def refreshAll(): Unit = {
    //example:
    //actor.ref ! keepersPerHour
  }

  override def onStart() { //kill
    //example:
    //cronTask(quartz, actor.ref, "0 0/10 * * * ?", keepersPerHour)
  }
}
