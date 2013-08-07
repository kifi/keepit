package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[FailedUriNormalizationRepoImpl])
trait FailedUriNormalizationRepo extends Repo[FailedUriNormalization]{
  def getByUrlHashes(urlHash: UrlHash, mappedUrlHash: UrlHash)(implicit session: RSession): Option[FailedUriNormalization]
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
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def mappedUrlHash = column[UrlHash]("mapped_url_hash", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def mappedUrl = column[String]("mapped_uri", O.NotNull)
    def failedCounts = column[Int]("failed_counts", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ urlHash ~ mappedUrlHash ~url ~ mappedUrl ~ state ~ failedCounts <> (FailedUriNormalization.apply _, FailedUriNormalization.unapply _)
  }

  def getByUrlHashes(urlHash: UrlHash, mappedUrlHash: UrlHash)(implicit session: RSession): Option[FailedUriNormalization] = {
    (for( r <- table if (r.urlHash === urlHash && r.mappedUrlHash === mappedUrlHash)) yield r).firstOption
  }

  def createOrIncrease(url: String, mappedUrl: String)(implicit session: RWSession): Unit = {
    val (urlHash, mappedUrlHash) = (NormalizedURIFactory.hashUrl(url), NormalizedURIFactory.hashUrl(mappedUrl))
    val r = (for( r <- table if (r.urlHash === urlHash && r.mappedUrlHash === mappedUrlHash)) yield r).firstOption
    r match {
      case Some(record) => save(record.withCounts(record.failedCounts + 1))
      case None => save( FailedUriNormalization(urlHash = urlHash, mappedUrlHash = mappedUrlHash, url = url, mappedUrl = mappedUrl, failedCounts = 1) )
    }
  }
}
