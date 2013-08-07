package com.keepit.reports

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.geckoboard._
import akka.actor._
import akka.util.Timeout
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorProvider
import com.keepit.inject._
import play.api.Plugin
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._


case object KeepsReport

private[reports] class GeckoboardReporterActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    geckoboardPublisher: GeckoboardPublisher)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  var i = 2

  def receive() = {
    case KeepsReport =>
      i = i + 1
      val data = NumberAndSecondaryStat(GeckoboardWidget.TotalKeepsPerHour, i, i * 2)
      geckoboardPublisher.publish(data)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait GeckoboardReporterPlugin extends SchedulingPlugin {
}

class GeckoboardReporterPluginImpl @Inject() (
    actorProvider: ActorProvider[GeckoboardReporterActor])//, val schedulingProperties: SchedulingProperties)
  extends GeckoboardReporterPlugin with Logging {
  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  implicit val actorTimeout = Timeout(60 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  override def onStart() {
    scheduleTask(actorProvider.system, 0 seconds, 10 minutes, actorProvider.actor, KeepsReport)
  }

}

