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
import org.joda.time.{LocalDate, Period, PeriodType, Months, Days}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import scala.slick.lifted.Query
import scala.slick.jdbc._
import com.keepit.common.cache._

case class UserRetentionKey(day: LocalDate, period: Period) extends Key[Int] {
  override val version = 0
  val namespace = "user_retention"
  def toKey(): String = day.toString + "_" + period.toString
}

class UserRetentionCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[UserRetentionKey, Int](innermostPluginSettings, innerToOuterPluginSettings:_*)

object UserQueries {
  val activeUsers = StaticQuery.query[(LocalDate, LocalDate, LocalDate, LocalDate), (Int, Option[Int])](
    s"""select u.id, b.bcount
      from (
        select id from user where updated_at between ? and ? and id not in (${UserIdSql.DontShowInAnalytics})
      ) u
      left join (
        select user_id, count(*) as bcount from bookmark where updated_at between ? and ? and state = 'active' and source != 'INIT_LOAD' group by user_id
      ) b
      on u.id = b.user_id""")
}

trait RetentionPerDay {
  val db: Database
  val clock: Clock
  val userRetentionCache: UserRetentionCache
}

trait RetentionWindow extends RetentionPerDay {
  implicit val dbMasterSlave = Database.Slave
  val period: Period

  def countsPerDay(day: LocalDate) = db.readOnly { implicit s =>
    val args = (day.minus(period.multipliedBy(3)), day.minus(period.multipliedBy(2)), day.minus(period.multipliedBy(1)), day)
    val userKeeps = UserQueries.activeUsers.list(args)
    (userKeeps.size, userKeeps.filter(_._2.isDefined).size)
  }

  def ratioPerDay(day: LocalDate): Int = userRetentionCache.getOrElse(UserRetentionKey(day, period)) {
    (countsPerDay(day) match {case (users, keepers) => 100d * (keepers.doubleValue / users)}).intValue
  }

  def data(): SparkLine = {
    val today = clock.today
    val ratioOfPeriod = (0 until 30) map today.minusDays map ratioPerDay
    SparkLine("30D Retention %", ratioOfPeriod.head, ratioOfPeriod.reverse)
  }
}

class RetentionOverMonth @Inject() (val db: Database, val clock: Clock, val userRetentionCache: UserRetentionCache)
  extends GeckoboardWidget[SparkLine](GeckoboardWidgetId("37507-ed12ca14-740b-449b-8998-d9f7fc909c80"))
  with RetentionWindow { val period = Months.months(1).toPeriod() }

class RetentionOverWeek @Inject() (val db: Database, val clock: Clock, val userRetentionCache: UserRetentionCache)
  extends GeckoboardWidget[SparkLine](GeckoboardWidgetId("37507-4bcc8716-c0ec-440e-9114-342d0a833d1f"))
  with RetentionWindow { val period = Days.days(7).toPeriod() }

class RetentionOverDay @Inject() (val db: Database, val clock: Clock, val userRetentionCache: UserRetentionCache)
  extends GeckoboardWidget[SparkLine](GeckoboardWidgetId("37507-893e9267-6539-48ce-9e16-5b599367c226"))
  with RetentionWindow { val period = Days.days(1).toPeriod() }


