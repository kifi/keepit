package com.keepit.rover.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class RoverUrlRule(
    id: Option[Id[RoverUrlRule]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[RoverUrlRule] = RoverUrlRuleStates.ACTIVE,
    pattern: String,
    proxy: Option[Id[RoverHttpProxy]]) extends ModelWithState[RoverUrlRule] {

  override def withId(id: Id[RoverUrlRule]): RoverUrlRule = this.copy(id = Some(id))
  override def withUpdateTime(now: DateTime): RoverUrlRule = this.copy(updatedAt = now)
  def isActive = this.state == RoverUrlRuleStates.ACTIVE

}

object RoverUrlRule {

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[RoverUrlRule]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[RoverUrlRule]) and
    (__ \ 'pattern).format[String] and
    (__ \ 'proxy).formatNullable(Id.format[RoverHttpProxy])
  )(RoverUrlRule.apply, unlift(RoverUrlRule.unapply))

}

object RoverUrlRuleStates extends States[RoverUrlRule]

@ImplementedBy(classOf[RoverUrlRuleRepoImpl])
trait RoverUrlRuleRepo extends Repo[RoverUrlRule] {
  def allActive(implicit session: RSession): Seq[RoverUrlRule]
}

@Singleton
class RoverUrlRuleRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val cache: RoverUrlRuleAllCache) extends DbRepo[RoverUrlRule] with RoverUrlRuleRepo {

  import db.Driver.simple._

  type RepoImpl = RoverUrlRepoTable
  class RoverUrlRepoTable(tag: Tag) extends RepoTable[RoverUrlRule](db, tag, "rover_url_rule") {

    def pattern = column[String]("pattern", O.NotNull)
    def proxy = column[Option[Id[RoverHttpProxy]]]("proxy_id", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, pattern, proxy) <> ((RoverUrlRule.apply _).tupled, RoverUrlRule.unapply _)

  }

  override def table(tag: Tag): RoverUrlRepoTable = new RoverUrlRepoTable(tag)
  initTable()

  override def deleteCache(model: RoverUrlRule)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: RoverUrlRule)(implicit session: RSession): Unit = {}

  def allActive(implicit session: RSession): Seq[RoverUrlRule] = {
    val result = cache.getOrElse(RoverUrlRuleAllKey()) {
      (for (f <- rows if f.state === RoverUrlRuleStates.ACTIVE) yield f).list
    }
    result.sortBy(_.id.get.id)
  }

}

case class RoverUrlRuleAllKey() extends Key[Seq[RoverUrlRule]] {
  override val version = 1
  val namespace = "rover_url_rule_all"
  def toKey(): String = "all"
}

class RoverUrlRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverUrlRuleAllKey, Seq[RoverUrlRule]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
