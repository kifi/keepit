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
import scala.slick.jdbc._

object UserIdSql {
  val DontShowInAnalytics = s"select user_id from user_experiment where experiment_type in ($DONT_SHOW_IN_ANALYTICS_STR)"
}

object KeepQueries {
  val KeepsByTime = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count(*) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")
  val UIKeepsByTime = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count(*) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and source != 'INIT_LOAD' and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")

}

trait KeepsPerTime {
  val query: StaticQuery1[(DateTime, DateTime), Int]
  val db: Database
  val bookmarkRepo: BookmarkRepo
  val clock: Clock
}

trait KeepsPerHour extends KeepsPerTime {
  implicit val dbMasterSlave = Database.Slave
  def data(): NumberAndSecondaryStat = {
    val now = clock.now
    val yesterday = now.minusDays(1)
    val (lastHour, hourAgo) = db.readOnly { implicit s =>
      (query.first(now.minusHours(1), now),
       query.first(yesterday.minusHours(1), yesterday))
    }
    NumberAndSecondaryStat(lastHour, hourAgo)
  }
}

class TotalKeepsPerHour @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb"))
  with KeepsPerHour { val query = KeepQueries.KeepsByTime }

class UIKeepsPerHour @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-8a571430-11a1-4f69-bfda-0e62fc814b4f"))
  with KeepsPerHour { val query = KeepQueries.UIKeepsByTime }

trait KeepsPerDay extends KeepsPerTime {
  implicit val dbMasterSlave = Database.Slave
  def data(): NumberAndSecondaryStat = {
    val now = clock.now
    val lastWeek = now.minusDays(7)
    val (lastDay, dayAgo) = db.readOnly { implicit s =>
      (query.first(now.minusDays(1), now),
       query.first(lastWeek.minusDays(1), lastWeek))
    }
    NumberAndSecondaryStat(lastDay, dayAgo)
  }
}

class TotalKeepsPerDay @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-fd4ca50c-42bc-4f77-973c-cf240831e4a4"))
  with KeepsPerDay { val query = KeepQueries.KeepsByTime }

class UIKeepsPerDay @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-23830d70-7b5e-4690-ab88-ae482d8484f3"))
  with KeepsPerDay { val query = KeepQueries.UIKeepsByTime }

trait KeepsPerWeek extends KeepsPerTime {
  implicit val dbMasterSlave = Database.Slave
  def data(): NumberAndSecondaryStat = {
    val (lastWeek, weekAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastWeek = now.minusDays(7)
      (query.first(now.minusDays(7), now),
       query.first(lastWeek.minusDays(7), lastWeek))
    }
    NumberAndSecondaryStat(lastWeek, weekAgo)
  }
}

class TotalKeepsPerWeek @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-f0758629-40c3-4d9f-9d90-452c2b3f3620"))
  with KeepsPerWeek { val query = KeepQueries.KeepsByTime }

class UIKeepsPerWeek @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-d7c4bed6-c213-46b5-a1f6-2d15966ace76"))
  with KeepsPerWeek { val query = KeepQueries.UIKeepsByTime }

trait KeepsPerMonth extends KeepsPerTime {
  implicit val dbMasterSlave = Database.Slave
  def data(): NumberAndSecondaryStat = {
    val (lastMonth, monthAgo) = db.readOnly { implicit s =>
      val now = clock.now
      val lastMonth = now.minusMonths(1)
      (query.first(now.minusMonths(1), now),
       query.first(lastMonth.minusMonths(1), lastMonth))
    }
    NumberAndSecondaryStat(lastMonth, monthAgo)
  }
}

class TotalKeepsPerMonth @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-ca144e78-c85d-4861-991a-2a30605a7c30"))
  with KeepsPerMonth { val query = KeepQueries.KeepsByTime }

class UIKeepsPerMonth @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-d6815e63-0bc0-4bf6-8be8-fe47d8d05e00"))
  with KeepsPerMonth { val query = KeepQueries.UIKeepsByTime }
