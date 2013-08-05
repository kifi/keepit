package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[UriNormalizationRuleRepoImpl])
trait UriNormalizationRuleRepo extends Repo[UriNormalizationRule] {
  def getByUrl(url: String)(implicit session: RSession): Option[String]
}

@Singleton
class UriNormalizationRuleRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[UriNormalizationRule] with UriNormalizationRuleRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[UriNormalizationRule](db, "uri_normalization_rule"){
    def url = column[String]("url", O.NotNull)
    def mappedUrl = column[String]("mapped_uri", O.NotNull)
    def * = id.? ~  createdAt ~ updatedAt ~ url ~ mappedUrl ~ state <> (UriNormalizationRule.apply _, UriNormalizationRule.unapply _)
  }

  def getByUrl(url: String)(implicit session: RSession): Option[String] = {
    (for(r <- table if r.url === url) yield r.mappedUrl).firstOption
  }

}