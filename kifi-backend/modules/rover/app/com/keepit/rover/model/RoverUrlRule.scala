package com.keepit.rover.model

import com.google.inject.Inject
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{DbRepo, DataBaseComponent, Repo}
import com.keepit.common.db.{ModelWithState, State, Id, States}
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.HttpProxy
import com.keepit.rover.rule.{UrlRuleAction, UrlRuleFilter}
import org.joda.time.DateTime

import scala.concurrent.duration.Duration

object RoverUrlRuleStates extends States[RoverUrlRule]

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

trait RoverUrlRuleRepo extends Repo[RoverUrlRule] {

}

class RoverUrlRuleRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val cache: RoverUrlRuleAllCache) extends DbRepo[RoverUrlRule] with RoverUrlRuleRepo {

  import db.Driver.simple._
  
  type RepoImpl = RoverUrlRepoTable
  class RoverUrlRepoTable(tag: Tag) extends RepoTable[RoverUrlRule](db, tag, "rover_url_rule") {
    
    def pattern = column[String]("pattern", O.NotNull)
    def proxy = column[Option[Id[RoverHttpProxy]]]("use_proxy", O.Nullable)
    
    def * = (id.?, createdAt, updatedAt, state, pattern, proxy) <> ((RoverUrlRule.apply _).tupled, RoverUrlRule.unapply _)
    
  }

  override def table(tag: Tag): RoverUrlRepoTable = new RoverUrlRepoTable(tag)
  initTable()

  override def deleteCache(model: RoverUrlRule)(implicit session: RSession): Unit = {}

  override def invalidateCache(model: RoverUrlRule)(implicit session: RSession): Unit = {}
}

case class RoverUrlRuleAllKey() extends Key[Seq[RoverUrlRule]] {
  override val version = 1
  val namespace = "rover_url_rule_all"
  def toKey(): String = "all"
}

class RoverUrlRuleAllCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[RoverUrlRuleAllKey, Seq[RoverUrlRuleAllCache]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
