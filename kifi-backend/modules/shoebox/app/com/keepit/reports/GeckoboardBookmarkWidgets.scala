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

  val all = "*"
  val users = "distinct user_id"

  def keepsByTime(what: String) = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count($what) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")

  def uiKeepsByTime(what: String) = StaticQuery.query[(DateTime, DateTime), Int](
    s"""select count($what) from bookmark
          where updated_at > ? and updated_at < ? and state = 'active' and source != 'INIT_LOAD' and
          user_id not in (${UserIdSql.DontShowInAnalytics})""")

}

import KeepQueries._

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
  with KeepsPerHour { val query = keepsByTime(all) }

class UIKeepsPerHour @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-8a571430-11a1-4f69-bfda-0e62fc814b4f"))
  with KeepsPerHour { val query = uiKeepsByTime(all) }

class KeepersPerHour @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-8f3caed0-a6d2-48de-a002-63a5b582aba2"))
  with KeepsPerHour { val query = uiKeepsByTime(users) }

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
  with KeepsPerDay { val query = keepsByTime(all) }

class UIKeepsPerDay @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-23830d70-7b5e-4690-ab88-ae482d8484f3"))
  with KeepsPerDay { val query = uiKeepsByTime(all) }

class KeepersPerDay @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-540b442a-765f-4c53-a4ce-3df86cbe2f80"))
  with KeepsPerDay { val query = uiKeepsByTime(users) }

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
  with KeepsPerWeek { val query = keepsByTime(all) }

class UIKeepsPerWeek @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-d7c4bed6-c213-46b5-a1f6-2d15966ace76"))
  with KeepsPerWeek { val query = uiKeepsByTime(all) }

class KeepersPerWeek @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-a48b3a3f-ae2c-4e77-91a6-8a416ee9696a"))
  with KeepsPerWeek { val query = uiKeepsByTime(users) }

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
  with KeepsPerMonth { val query = keepsByTime(all) }

class UIKeepsPerMonth @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-d6815e63-0bc0-4bf6-8be8-fe47d8d05e00"))
  with KeepsPerMonth { val query = uiKeepsByTime(all) }

class KeepersPerMonth @Inject() (val db: Database, val bookmarkRepo: BookmarkRepo, val clock: Clock)
  extends GeckoboardWidget[NumberAndSecondaryStat](GeckoboardWidgetId("37507-687e1343-221b-46a4-85ad-519d532e6ee1"))
  with KeepsPerMonth { val query = uiKeepsByTime(users) }

