package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id

// todo(Léo): break up this repo amd move proxy rules to sraper db
@ImplementedBy(classOf[UrlPatternRuleRepoImpl])
trait UrlPatternRuleRepo extends Repo[UrlPatternRule] {
  def getUrlPatternRules()(implicit session: RSession): UrlPatternRules
}

@Singleton
class UrlPatternRuleRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val urlPatternRulesAllCache: UrlPatternRulesAllCache,
  httpProxyRepo: HttpProxyRepo)
    extends DbRepo[UrlPatternRule] with UrlPatternRuleRepo {
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = UrlPatternRuleTable
  case class UrlPatternRuleTable(tag: Tag) extends RepoTable[UrlPatternRule](db, tag, "url_pattern_rule") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[Option[String]]("example", O.Nullable)
    def isUnscrapable = column[Boolean]("is_unscrapable", O.NotNull)
    def useProxy = column[Option[Id[HttpProxy]]]("use_proxy", O.Nullable)
    def normalization = column[Option[Normalization]]("normalization", O.Nullable)
    def trustedDomain = column[Option[String]]("trusted_domain", O.Nullable)
    def nonSensitive = column[Option[Boolean]]("non_sensitive", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, pattern, example, isUnscrapable, useProxy, normalization, trustedDomain, nonSensitive) <> ((UrlPatternRule.apply _).tupled, UrlPatternRule.unapply _)
  }

  def table(tag: Tag) = new UrlPatternRuleTable(tag)

  override def invalidateCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRulesAllCache.remove(UrlPatternRulesAllKey())
  }

  override def deleteCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRulesAllCache.remove(UrlPatternRulesAllKey())
  }

  def getUrlPatternRules()(implicit session: RSession): UrlPatternRules = {
    urlPatternRulesAllCache.getOrElse(UrlPatternRulesAllKey()) {
      val result = (for (f <- rows if f.state === UrlPatternRuleStates.ACTIVE) yield f).list
      val sortedResult = result.sortBy(_.id.get.id)
      UrlPatternRules(sortedResult)
    }
  }
}

