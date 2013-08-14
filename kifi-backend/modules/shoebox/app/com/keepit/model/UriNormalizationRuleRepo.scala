package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.cache.{Key, StringCacheImpl, JsonCacheImpl, FortyTwoCachePlugin}
import scala.concurrent.duration.Duration

@ImplementedBy(classOf[UriNormalizationRuleRepoImpl])
trait UriNormalizationRuleRepo extends Repo[UriNormalizationRule] {
  def getByUrl(prepUrl: String)(implicit session: RSession): Option[String]
}

@Singleton
class UriNormalizationRuleRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock,
  prepUrlHashCache: PrepUrlHashToMappedUrlCache
) extends DbRepo[UriNormalizationRule] with UriNormalizationRuleRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UriNormalizationRule](db, "uri_normalization_rule"){
    def prepUrlHash = column[UrlHash]("prep_url_hash", O.NotNull)
    def prepUrl = column[String]("prep_url", O.NotNull)
    def mappedUrl = column[String]("mapped_url", O.NotNull)
    def * = id.? ~  createdAt ~ updatedAt ~ prepUrlHash ~ prepUrl ~ mappedUrl ~ state <> (UriNormalizationRule.apply _, UriNormalizationRule.unapply _)
  }

  def getByUrl(prepUrl: String)(implicit session: RSession): Option[String] = {
    val prepUrlHash = NormalizedURI.hashUrl(prepUrl)
    prepUrlHashCache.getOrElseOpt(PrepUrlHashKey(prepUrlHash)) {
      (for(r <- table if r.prepUrlHash === prepUrlHash) yield r.mappedUrl).firstOption
    }
  }

  override def invalidateCache(rule: UriNormalizationRule)(implicit session: RSession) = {
    prepUrlHashCache.set(PrepUrlHashKey(rule.prepUrlHash), rule.mappedUrl)
    rule
  }
}

case class PrepUrlHashKey(prepUrlHash: UrlHash) extends Key[String] {
  override val version = 0
  val namespace = "mappedUrl_by_prepUrlHash"
  def toKey(): String = prepUrlHash.hash
}

class PrepUrlHashToMappedUrlCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[PrepUrlHashKey](innermostPluginSettings, innerToOuterPluginSettings:_*)
