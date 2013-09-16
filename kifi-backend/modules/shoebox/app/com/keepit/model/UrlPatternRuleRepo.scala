package com.keepit.model

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import scala.Some

@ImplementedBy(classOf[UrlPatternRuleRepoImpl])
trait UrlPatternRuleRepo extends Repo[UrlPatternRule] {
  def allActive()(implicit session: RSession): Seq[UrlPatternRule]
  def get(url: String)(implicit session: RSession): Seq[UrlPatternRule]
  def getUnique(url: String)(implicit sesseion: RSession): Option[UrlPatternRule]
  def isUnscrapable(url: String)(implicit session: RSession): Boolean
  def getTrustedDomain(url: String)(implicit session: RSession): Option[String]
  def getPreferredNormalization(url: String)(implicit session: RSession): Option[Normalization]
  def normSchemePage(page: Int, pageSize: Int)(implicit session: RSession): (Seq[UrlPatternRule], Int)
}

@Singleton
class UrlPatternRuleRepoImpl @Inject() (
                                      val db: DataBaseComponent,
                                      val clock: Clock,
                                      val urlPatternRuleAllCache: UrlPatternRuleAllCache)
  extends DbRepo[UrlPatternRule] with UrlPatternRuleRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._
  import scala.util.matching.Regex

  override val table = new RepoTable[UrlPatternRule](db, "url_pattern_rule") {
    def pattern = column[String]("pattern", O.NotNull)
    def isUnscrapable = column[Boolean]("is_unscrapable", O.NotNull)
    def normalization = column[Normalization]("normalization", O.Nullable)
    def trustedDomain = column[String]("trusted_domain", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ state ~ pattern ~ isUnscrapable ~ normalization.? ~ trustedDomain.? <> (UrlPatternRule.apply _, UrlPatternRule.unapply _)
  }

  private var allMemCache: Option[Seq[UrlPatternRule]] = None

  override def invalidateCache(urlPatternRule: UrlPatternRule)(implicit session: RSession) = {
    urlPatternRuleAllCache.remove(UrlPatternRuleAllKey())
    allMemCache = None
    urlPatternRule
  }

  def allActive()(implicit session: RSession): Seq[UrlPatternRule] =
    allMemCache.getOrElse {
      val result = urlPatternRuleAllCache.getOrElse(UrlPatternRuleAllKey()) {
        (for(f <- table if f.state === UrlPatternRuleStates.ACTIVE) yield f).list
      }
      allMemCache = Some(result)
      result
    }

  def get(url: String)(implicit session: RSession): Seq[UrlPatternRule] = allActive().filter(rule => url.matches(rule.pattern))
  def getUnique(url: String)(implicit sesseion: RSession): Option[UrlPatternRule] = {
    val matchingRules = get(url)
    require(matchingRules.length <= 1, s"${matchingRules.length} rules match url ${url}")
    matchingRules.headOption
  }
  def isUnscrapable(url: String)(implicit session: RSession): Boolean = getUnique(url).map(_.isUnscrapable).getOrElse(false)
  def getTrustedDomain(url: String)(implicit session: RSession): Option[String] = for { rule <- getUnique(url); trustedDomain <- rule.trustedDomain } yield trustedDomain
  def getPreferredNormalization(url: String)(implicit session: RSession): Option[Normalization] = for { rule <- getUnique(url); normalization <- rule.normalization } yield normalization

  def normSchemePage(page: Int, pageSize: Int)(implicit session: RSession): (Seq[UrlPatternRule], Int) = {
    val ds = (for (d <- table if d.state =!= UrlPatternRuleStates.INACTIVE && d.normalization.isNotNull) yield d).sortBy(_.pattern).list
    (ds.drop(page*pageSize).take(pageSize), ds.size)
  }
}
