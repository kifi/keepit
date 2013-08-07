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
    def prepUrlHash = column[UrlHash]("prep_url_hash", O.NotNull)
    def mappedUrlHash = column[UrlHash]("mapped_url_hash", O.NotNull)
    def prepUrl = column[String]("prep_url", O.NotNull)
    def mappedUrl = column[String]("mapped_url", O.NotNull)
    def counts = column[Int]("counts", O.NotNull)
    def lastContentCheck = column[DateTime]("last_content_check", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ prepUrlHash ~ mappedUrlHash ~ prepUrl ~ mappedUrl ~ state ~ counts ~ lastContentCheck <> (FailedUriNormalization.apply _, FailedUriNormalization.unapply _)
  }

  def getByUrlHashes(prepUrlHash: UrlHash, mappedUrlHash: UrlHash)(implicit session: RSession): Option[FailedUriNormalization] = {
    (for( r <- table if (r.prepUrlHash === prepUrlHash && r.mappedUrlHash === mappedUrlHash)) yield r).firstOption
  }

  def createOrIncrease(prepUrl: String, mappedUrl: String)(implicit session: RWSession): Unit = {
    val (prepUrlHash, mappedUrlHash) = (NormalizedURI.hashUrl(prepUrl), NormalizedURI.hashUrl(mappedUrl))
    val r = (for( r <- table if (r.prepUrlHash === prepUrlHash && r.mappedUrlHash === mappedUrlHash)) yield r).firstOption
    r match {
      case Some(record) => save(record.withCounts(record.counts + 1))
      case None => save( FailedUriNormalization(prepUrlHash = prepUrlHash, mappedUrlHash = mappedUrlHash, prepUrl = prepUrl, mappedUrl = mappedUrl, counts = 1, lastContentCheck = currentDateTime) )
    }
  }
}
