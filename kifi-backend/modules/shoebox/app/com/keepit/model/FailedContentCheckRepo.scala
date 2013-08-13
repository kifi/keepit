package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

@ImplementedBy(classOf[FailedContentCheckRepoImpl])
trait FailedContentCheckRepo extends Repo[FailedContentCheck]{
  def getByUrls(url1: String, url2: String)(implicit session: RSession): Option[FailedContentCheck]
  def createOrIncrease(url1: String, url2: String)(implicit session: RWSession): Unit
}

@Singleton
class FailedContentCheckRepoImpl @Inject()(
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[FailedContentCheck] with FailedContentCheckRepo{
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[FailedContentCheck](db, "failed_uri_normalization"){
    def url1Hash = column[UrlHash]("url1_hash", O.NotNull)
    def url2Hash = column[UrlHash]("url2_hash", O.NotNull)
    def url1 = column[String]("url1", O.NotNull)
    def url2 = column[String]("url2", O.NotNull)
    def counts = column[Int]("counts", O.NotNull)
    def lastContentCheck = column[DateTime]("last_content_check", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ url1Hash ~ url2Hash ~ url1 ~ url2 ~ state ~ counts ~ lastContentCheck <> (FailedContentCheck.apply _, FailedContentCheck.unapply _)
  }

  private def sortUrls(url1: String, url2: String): (String, String) = if (url1.compareTo(url2) < 0) (url1, url2) else (url2, url1)

  def getByUrls(url1: String, url2: String)(implicit session: RSession): Option[FailedContentCheck] = {
    val (sorted1, sorted2) = sortUrls(url1, url2)
    val (hash1, hash2) = (NormalizedURI.hashUrl(sorted1), NormalizedURI.hashUrl(sorted2))
    (for( r <- table if (r.url1Hash === hash1 && r.url2Hash === hash2)) yield r).firstOption
  }

  def createOrIncrease(url1: String, url2: String)(implicit session: RWSession): Unit = {
    val (sorted1, sorted2) = sortUrls(url1, url2)
    val (hash1, hash2) = (NormalizedURI.hashUrl(sorted1), NormalizedURI.hashUrl(sorted2))
    val r = (for( r <- table if (r.url1Hash === hash1 && r.url2Hash === hash2) ) yield r).firstOption
    r match {
      case Some(record) => save(record.withCounts(record.counts + 1))
      case None => save( FailedContentCheck(url1Hash = hash1, url2Hash = hash2, url1 = sorted1, url2 = sorted2, counts = 1, lastContentCheck = currentDateTime) )
    }
  }
}
