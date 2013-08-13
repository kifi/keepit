package com.keepit.reports

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.geckoboard._
import akka.actor._
import akka.util.Timeout
import com.keepit.model.{BookmarkRepo, BookmarkSource}
import com.keepit.model.ExperimentTypes.DONT_SHOW_IN_ANALYTICS_STR
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
import org.joda.time.DateTime
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import scala.slick.lifted.Query
import scala.slick.jdbc.StaticQuery

object UserIdSql {
  val DontShowInAnalytics = s"select user_id from user_experiment where experiment_type in ($DONT_SHOW_IN_ANALYTICS_STR)"
}

object KeepQueries {
  val KeepsByTime = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count(*) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")
  val HoverKeepsByTime = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count(*) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and source in ('HOVER_KEEP', 'SITE') and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")

}

class TotalKeepsPerHour @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb")) {
  implicit val dbMasterSlave = Database.Slave
  import KeepQueries._

  def data(): NumberAndSecondaryStat = {
    val now = clock.now
    val yesterday = now.minusDays(1)
    val (lastHour, hourAgo) = db.readOnly { implicit s =>
      (KeepsByTime.first(now.minusHours(1), now),
       KeepsByTime.first(yesterday.minusHours(1), yesterday))
    }
    NumberAndSecondaryStat(lastHour, hourAgo)
  }
}

class TotalKeepsPerDay @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-fd4ca50c-42bc-4f77-973c-cf240831e4a4")) {
  import KeepQueries._
  implicit val dbMasterSlave = Database.Slave

  def data(): NumberAndSecondaryStat = {
    val now = clock.now
    val lastWeek = now.minusDays(7)
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      (KeepsByTime.first(now.minusDays(1), now),
       KeepsByTime.first(lastWeek.minusDays(1), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

class TotalKeepsPerWeek @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-f0758629-40c3-4d9f-9d90-452c2b3f3620")) {
  import KeepQueries._
  implicit val dbMasterSlave = Database.Slave

  def data(): NumberAndSecondaryStat = {
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastWeek = now.minusDays(7)
      (KeepsByTime.first(now.minusDays(7), now),
       KeepsByTime.first(lastWeek.minusDays(7), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

class HoverKeepsPerWeek @Inject() (
    db: Database,
    bookmarkRepo: BookmarkRepo,
    clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-d7c4bed6-c213-46b5-a1f6-2d15966ace76")) {
  import KeepQueries._
  implicit val dbMasterSlave = Database.Slave
  val hover = BookmarkSource("HOVER_KEEP")

  def data(): NumberAndSecondaryStat = {
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastWeek = now.minusDays(7)
      (HoverKeepsByTime.first(now.minusDays(7), now),
       HoverKeepsByTime.first(lastWeek.minusDays(7), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

