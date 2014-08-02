package com.keepit.model

import com.google.inject.{ Provides, ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import net.codingwell.scalaguice.ScalaModule

trait UrlPatternRuleRepo extends Repo[UrlPatternRule] {
  def rules(): UrlPatternRules
  def getProxy(url: String)(implicit session: RSession): Option[HttpProxy]
  def loadCache()(implicit session: RSession): Unit
}

@Singleton
class UrlPatternRuleRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val urlPatternRuleAllCache: UrlPatternRuleAllCache,
  httpProxyRepo: HttpProxyRepo)
    extends DbRepo[UrlPatternRule] with UrlPatternRuleRepo {
  import db.Driver.simple._
  import DBSession._

  type RepoImpl = UrlPatternRuleTable
  case class UrlPatternRuleTable(tag: Tag) extends RepoTable[UrlPatternRule](db, tag, "url_pattern_rule") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[String]("example", O.Nullable)
    def isUnscrapable = column[Boolean]("is_unscrapable", O.NotNull)
    def useProxy = column[Id[HttpProxy]]("use_proxy", O.Nullable)
    def normalization = column[Normalization]("normalization", O.Nullable)
    def trustedDomain = column[String]("trusted_domain", O.Nullable)
    def nonSensitive = column[Boolean]("non_sensitive")
    def * = (id.?, createdAt, updatedAt, state, pattern, example.?, isUnscrapable, useProxy.?, normalization.?, trustedDomain.?, nonSensitive) <> ((UrlPatternRule.apply _).tupled, UrlPatternRule.unapply _)
  }

  def table(tag: Tag) = new UrlPatternRuleTable(tag)

  private var allMemCache: Option[UrlPatternRules] = None

  override def invalidateCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRuleAllCache.remove(UrlPatternRuleAllKey())
    loadCache()
  }

  override def deleteCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRuleAllCache.remove(UrlPatternRuleAllKey())
    loadCache()
  }

  def loadCache()(implicit session: RSession): Unit = {
    val result = urlPatternRuleAllCache.getOrElse(UrlPatternRuleAllKey()) {
      (for (f <- rows if f.state === UrlPatternRuleStates.ACTIVE) yield f).list
    }
    val sortedResult = result.sortBy(_.id.get.id)
    allMemCache = Some(UrlPatternRules(sortedResult))
  }

  def rules(): UrlPatternRules = allMemCache.get

  def getProxy(url: String)(implicit session: RSession): Option[HttpProxy] = for {
    rule <- allMemCache.get.findFirst(url)
    proxyId <- rule.useProxy
    proxy <- httpProxyRepo.allActive().find(_.id == Some(proxyId))
  } yield proxy // todo(Léo): break up this repo amd move proxy rules to sraper db
}

case class UrlPatternRules(rules: Seq[UrlPatternRule]) {
  private[model] def findFirst(url: String): Option[UrlPatternRule] = rules.find(rule => url.matches(rule.pattern))
  def isUnscrapable(url: String): Boolean = findFirst(url).map(_.isUnscrapable).getOrElse(false)
  def getTrustedDomain(url: String): Option[String] = for { rule <- findFirst(url); trustedDomain <- rule.trustedDomain } yield trustedDomain
  def getPreferredNormalization(url: String): Option[Normalization] = for { rule <- findFirst(url); normalization <- rule.normalization } yield normalization
}

case class UrlPatternRuleModule() extends ScalaModule {
  def configure() = {}

  @Provides @Singleton
  def UrlPatternRuleRepoProvider(db: Database, urlPatternRuleRepo: UrlPatternRuleRepoImpl): UrlPatternRuleRepo = {
    db.readWrite { implicit session =>
      urlPatternRuleRepo.loadCache()
    }
    urlPatternRuleRepo
  }
}
