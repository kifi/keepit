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

class TotalKeepsPerHour @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb")) {
  implicit val dbMasterSlave = Database.Slave

  def data(): NumberAndSecondaryStat = {
    val (lastHour, hourAgo) = db.readOnly { implicit s =>
      val now = clock.now
      (bookmarkRepo.getCountByTime(now.minusHours(1), now),
       bookmarkRepo.getCountByTime(now.minusHours(2), now.minusHours(1)))
    }
    NumberAndSecondaryStat(lastHour, hourAgo)
  }
}

private[reports] class GeckoboardReporterActor @Inject() (
  healthcheckPlugin: HealthcheckPlugin,
  geckoboardPublisher: GeckoboardPublisher)
extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case widget: TotalKeepsPerHour => geckoboardPublisher.publish(widget)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait GeckoboardReporterPlugin extends SchedulingPlugin {
}

class GeckoboardReporterPluginImpl @Inject() (
    actorProvider: ActorProvider[GeckoboardReporterActor],
    totalKeepsPerHour: TotalKeepsPerHour,
    val schedulingProperties: SchedulingProperties)
extends GeckoboardReporterPlugin with Logging {
//  val schedulingProperties = SchedulingProperties.AlwaysEnabled

  implicit val actorTimeout = Timeout(60 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true

  override def onStart() {
    scheduleTask(actorProvider.system, 0 seconds, 10 minutes, actorProvider.actor, totalKeepsPerHour)
    // scheduleTask(actorProvider.system, 0 seconds, 1 hours, actorProvider.actor, KeepsDailyReport)
  }
}
