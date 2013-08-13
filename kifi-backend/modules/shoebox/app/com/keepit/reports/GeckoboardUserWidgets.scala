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
import org.joda.time._
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import scala.slick.lifted.Query
import scala.slick.jdbc._

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
}

trait RetentionWindow extends RetentionPerDay {
  implicit val dbMasterSlave = Database.Slave

  def countsPerDay(day: LocalDate) = db.readOnly { implicit s =>
    val userKeeps = UserQueries.activeUsers.list((day.minusMonths(3), day.minusMonths(2), day.minusMonths(1), day))
    (userKeeps.size, userKeeps.filter(_._2.isDefined).size)
  }

  def ratioPerDay(day: LocalDate): Int =
    (countsPerDay(day) match {case (users, keepers) => 100d * (keepers.doubleValue / users)}).intValue

  def data(): SparkLine = {
    val today = clock.today
    val ratioOfMonth = (0 until 30) map today.minusDays map ratioPerDay
    SparkLine("Retention Per Month", ratioOfMonth.head, ratioOfMonth)
  }
}

class RetentionOverMonth @Inject() (val db: Database, val clock: Clock)
  extends GeckoboardWidget[SparkLine](GeckoboardWidgetId("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb"))
  with RetentionWindow


