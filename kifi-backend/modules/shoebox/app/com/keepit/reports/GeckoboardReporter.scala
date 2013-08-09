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
import com.keepit.common.actor.ActorProvider
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
    actorProvider: ActorProvider[GeckoboardReporterActor],
    quartz: ActorProvider[QuartzActor],
    val schedulingProperties: SchedulingProperties,
    totalKeepsPerHour: TotalKeepsPerHour,
    totalKeepsPerDay: TotalKeepsPerDay,
    totalKeepsPerWeek: TotalKeepsPerWeek,
    hoverKeepsPerWeek: HoverKeepsPerWeek)
extends GeckoboardReporterPlugin with Logging {

  implicit val actorTimeout = Timeout(60 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  def refreshAll(): Unit = {
    actorProvider.actor ! totalKeepsPerHour
    actorProvider.actor ! totalKeepsPerDay
    actorProvider.actor ! totalKeepsPerWeek
    actorProvider.actor ! hoverKeepsPerWeek
  }

  override def onStart() {
    cronTask(quartz, actorProvider.actor, "0 0/10 * * * ?", totalKeepsPerHour)
    cronTask(quartz, actorProvider.actor, "0 0 * * * ?", totalKeepsPerDay)
    cronTask(quartz, actorProvider.actor, "0 0 0/6 * * ?", totalKeepsPerWeek)
    cronTask(quartz, actorProvider.actor, "0 0 0/6 * * ?", hoverKeepsPerWeek)
  }
}
