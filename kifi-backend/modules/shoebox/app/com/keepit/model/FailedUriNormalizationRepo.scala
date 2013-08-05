package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[FailedUriNormalizationRepoImpl])
trait FailedUriNormalizationRepo extends Repo[FailedUriNormalization]{
  def getByUrls(url: String, mappedUrl: String)(implicit session: RSession): Option[FailedUriNormalization]
  def createOrIncrease(url: String, mappedUrl: String)(implicit session: RWSession): Unit
}

@Singleton
class FailedUriNormalizationRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[FailedUriNormalization] with FailedUriNormalizationRepo{
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[FailedUriNormalization](db, "failed_uri_normalization"){
    def url = column[String]("url", O.NotNull)
    def mappedUrl = column[String]("mapped_uri", O.NotNull)
    def failedCounts = column[Int]("failed_counts", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ url ~ mappedUrl ~ state ~ failedCounts <> (FailedUriNormalization.apply _, FailedUriNormalization.unapply _)
  }

  def getByUrls(url: String, mappedUrl: String)(implicit session: RSession): Option[FailedUriNormalization] = {
    (for( r <- table if (r.url === url && r.mappedUrl === mappedUrl)) yield r).firstOption
  }

  def createOrIncrease(url: String, mappedUrl: String)(implicit session: RWSession): Unit = {
    val r = (for( r <- table if (r.url === url && r.mappedUrl === mappedUrl)) yield r).firstOption
    r match {
      case Some(record) => save(record.withCounts(record.failedCounts + 1))
      case None => save( FailedUriNormalization(url = url, mappedUrl = mappedUrl, failedCounts = 1) )
    }
  }
}