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
      val yesterday = now.minusDays(1)
      (bookmarkRepo.getCountByTime(now.minusHours(1), now),
       bookmarkRepo.getCountByTime(yesterday.minusHours(1), yesterday))
    }
    NumberAndSecondaryStat(lastHour, hourAgo)
  }
}

class TotalKeepsPerDay @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-fd4ca50c-42bc-4f77-973c-cf240831e4a4")) {
  implicit val dbMasterSlave = Database.Slave

  def data(): NumberAndSecondaryStat = {
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastWeek = now.minusDays(7)
      (bookmarkRepo.getCountByTime(now.minusDays(1), now),
       bookmarkRepo.getCountByTime(lastWeek.minusDays(1), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

class TotalKeepsPerWeek @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-f0758629-40c3-4d9f-9d90-452c2b3f3620")) {
  implicit val dbMasterSlave = Database.Slave

  def data(): NumberAndSecondaryStat = {
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastWeek = now.minusDays(7)
      (bookmarkRepo.getCountByTime(now.minusDays(7), now),
       bookmarkRepo.getCountByTime(lastWeek.minusDays(7), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

