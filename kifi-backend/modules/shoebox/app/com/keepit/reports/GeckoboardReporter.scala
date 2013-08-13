package com.keepit.reports

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.geckoboard._
import akka.actor._
import akka.util.Timeout
import com.keepit.model.BookmarkRepo
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.time._
import com.keepit.inject._
import play.api.Plugin
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import us.theatr.akka.quartz.QuartzActor

private[reports] class GeckoboardReporterActor @Inject() (
  healthcheckPlugin: HealthcheckPlugin,
  geckoboardPublisher: GeckoboardPublisher)

extends FortyTwoActor(healthcheckPlugin) with Logging {
  def receive() = {
    case widget: GeckoboardWidget[_] => geckoboardPublisher.publish(widget)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait GeckoboardReporterPlugin extends SchedulingPlugin {
  def refreshAll(): Unit
}

class GeckoboardReporterPluginImpl @Inject() (
    actor: ActorInstance[GeckoboardReporterActor],
    quartz: ActorInstance[QuartzActor],
    val schedulingProperties: SchedulingProperties,
    totalKeepsPerHour: TotalKeepsPerHour,
    uiKeepsPerHour: UIKeepsPerHour,
    totalKeepsPerDay: TotalKeepsPerDay,
    uiKeepsPerDay: UIKeepsPerDay,
    totalKeepsPerWeek: TotalKeepsPerWeek,
    uiKeepsPerWeek: UIKeepsPerWeek)
extends GeckoboardReporterPlugin with Logging {

  implicit val actorTimeout = Timeout(60 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  def refreshAll(): Unit = {
    actor.ref ! totalKeepsPerHour
    actor.ref ! uiKeepsPerHour
    actor.ref ! totalKeepsPerDay
    actor.ref ! uiKeepsPerDay
    actor.ref ! totalKeepsPerWeek
    actor.ref ! uiKeepsPerWeek
  }

  override def onStart() {
    cronTask(quartz, actor.ref, "0 0/10 * * * ?", totalKeepsPerHour)
    cronTask(quartz, actor.ref, "0 0/10 * * * ?", uiKeepsPerHour)
    cronTask(quartz, actor.ref, "0 0 * * * ?", totalKeepsPerDay)
    cronTask(quartz, actor.ref, "0 0 * * * ?", uiKeepsPerDay)
    cronTask(quartz, actor.ref, "0 0 0/6 * * ?", totalKeepsPerWeek)
    cronTask(quartz, actor.ref, "0 0 0/6 * * ?", uiKeepsPerWeek)
  }
}
