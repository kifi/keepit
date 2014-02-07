package com.keepit.model

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id

@ImplementedBy(classOf[UrlPatternRuleRepoImpl])
trait UrlPatternRuleRepo extends Repo[UrlPatternRule] {
  def allActive()(implicit session: RSession): Seq[UrlPatternRule]
  def findAll(url: String)(implicit session: RSession): Seq[UrlPatternRule]
  def findFirst(url: String)(implicit sesseion: RSession): Option[UrlPatternRule]
  def isUnscrapable(url: String)(implicit session: RSession): Boolean
  def getProxy(url: String)(implicit session: RSession): Option[HttpProxy]
  def getTrustedDomain(url: String)(implicit session: RSession): Option[String]
  def getPreferredNormalization(url: String)(implicit session: RSession): Option[Normalization]
}

@Singleton
class UrlPatternRuleRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val urlPatternRuleAllCache: UrlPatternRuleAllCache,
  httpProxyRepo: HttpProxyRepo)
  extends DbRepo[UrlPatternRule] with UrlPatternRuleRepo {
  import FortyTwoTypeMappers._
  import db.Driver.simple._
  import DBSession._

  case class UrlPatternRuleTable(tag: Tag) extends RepoTable[UrlPatternRule](db, tag, "url_pattern_rule") {
    def pattern = column[String]("pattern", O.NotNull)
    def example = column[String]("example", O.Nullable)
    def isUnscrapable = column[Boolean]("is_unscrapable", O.NotNull)
    def useProxy = column[Id[HttpProxy]]("use_proxy", O.Nullable)
    def normalization = column[Normalization]("normalization", O.Nullable)
    def trustedDomain = column[String]("trusted_domain", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, pattern, example.?, isUnscrapable, useProxy.?, normalization.?, trustedDomain.?) <> ((UrlPatternRule.apply _).tupled, UrlPatternRule.unapply _)
  }

  private var allMemCache: Option[Seq[UrlPatternRule]] = None

  override def invalidateCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRuleAllCache.remove(UrlPatternRuleAllKey())
    allMemCache = None
  }

  override def deleteCache(urlPatternRule: UrlPatternRule)(implicit session: RSession): Unit = {
    urlPatternRuleAllCache.remove(UrlPatternRuleAllKey())
    allMemCache = None
  }

  def allActive()(implicit session: RSession): Seq[UrlPatternRule] =
    allMemCache.getOrElse {
      val result = urlPatternRuleAllCache.getOrElse(UrlPatternRuleAllKey()) {
        (for(f <- rows if f.state === UrlPatternRuleStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result.sortBy(_.id.get.id)
    }

  def findAll(url: String)(implicit session: RSession): Seq[UrlPatternRule] = allActive().filter(rule => url.matches(rule.pattern))
  def findFirst(url: String)(implicit session: RSession): Option[UrlPatternRule] = allActive().find(rule => url.matches(rule.pattern))
  def isUnscrapable(url: String)(implicit session: RSession): Boolean = findFirst(url).map(_.isUnscrapable).getOrElse(false)
  def getProxy(url: String)(implicit session: RSession): Option[HttpProxy] = for { rule <- findFirst(url); proxyId <- rule.useProxy; proxy <- httpProxyRepo.allActive().find(_.id == Some(proxyId)) } yield proxy // todo(Léo): break up this repo amd move proxy rules to sraper db
  def getTrustedDomain(url: String)(implicit session: RSession): Option[String] = for { rule <- findFirst(url); trustedDomain <- rule.trustedDomain } yield trustedDomain
  def getPreferredNormalization(url: String)(implicit session: RSession): Option[Normalization] = for { rule <- findFirst(url); normalization <- rule.normalization } yield normalization
}
