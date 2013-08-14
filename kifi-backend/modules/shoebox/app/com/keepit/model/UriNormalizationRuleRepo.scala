package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[UriNormalizationRuleRepoImpl])
trait UriNormalizationRuleRepo extends Repo[UriNormalizationRule] {
  def getByUrl(prepUrl: String)(implicit session: RSession): Option[String]
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

  override def save(model: UriNormalizationRule)(implicit session: RWSession): UriNormalizationRule = {
    getRuleObjectByUrl(model.prepUrl) match {
      case None => super.save(model)
      case Some(rule) => super.save(rule.copy(updatedAt = model.updatedAt, mappedUrl = model.mappedUrl))           // modify an existing rule. Make sure rule is unique.
    }
  }

  private def getRuleObjectByUrl(prepUrl: String)(implicit session: RSession): Option[UriNormalizationRule] = {
    (for(r <- table if r.prepUrlHash === NormalizedURI.hashUrl(prepUrl)) yield r).firstOption
  }


  def getByUrl(prepUrl: String)(implicit session: RSession): Option[String] = {
    (for(r <- table if r.prepUrlHash === NormalizedURI.hashUrl(prepUrl)) yield r.mappedUrl).firstOption
  }

}
