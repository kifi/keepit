package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[UriNormalizationRuleRepoImpl])
trait UriNormalizationRuleRepo extends Repo[UriNormalizationRule] {
  def getByUrlHash(urlHash: UrlHash)(implicit session: RSession): Option[String]
}

@Singleton
class UriNormalizationRuleRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[UriNormalizationRule] with UriNormalizationRuleRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UriNormalizationRule](db, "uri_normalization_rule"){
    def prepUrlHash = column[UrlHash]("prep_url_hash", O.NotNull)
    def prepUrl = column[String]("prep_url", O.NotNull)
    def mappedUrl = column[String]("mapped_url", O.NotNull)
    def * = id.? ~  createdAt ~ updatedAt ~ prepUrlHash ~ prepUrl ~ mappedUrl ~ state <> (UriNormalizationRule.apply _, UriNormalizationRule.unapply _)
  }

  def getByUrlHash(prepUrlHash: UrlHash)(implicit session: RSession): Option[String] = {
    (for(r <- table if r.prepUrlHash === prepUrlHash) yield r.mappedUrl).firstOption
  }

}
