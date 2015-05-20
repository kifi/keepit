package com.keepit.model

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[FailedContentCheckRepoImpl])
trait FailedContentCheckRepo extends Repo[FailedContentCheck] {
  def getByUrls(url1: String, url2: String)(implicit session: RSession): Option[FailedContentCheck]
  def createOrIncrease(url1: String, url2: String)(implicit session: RWSession): Unit
  def contains(url1: String, url2: String)(implicit session: RSession): Boolean
  def getRecentCountByURL(url: String, since: DateTime)(implicit session: RSession): Int
}

@Singleton
class FailedContentCheckRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[FailedContentCheck] with FailedContentCheckRepo {
  import db.Driver.simple._

  type RepoImpl = FailedContentCheckTable
  case class FailedContentCheckTable(tag: Tag) extends RepoTable[FailedContentCheck](db, tag, "failed_content_check") {
    def url1Hash = column[UrlHash]("url1_hash", O.NotNull)
    def url2Hash = column[UrlHash]("url2_hash", O.NotNull)
    def url1 = column[String]("url1", O.NotNull)
    def url2 = column[String]("url2", O.NotNull)
    def counts = column[Int]("counts", O.NotNull)
    def lastContentCheck = column[DateTime]("last_content_check", O.NotNull)
    def * = (id.?, createdAt, updatedAt, url1Hash, url2Hash, url1, url2, state, counts, lastContentCheck) <> (FailedContentCheck.tupled, FailedContentCheck.unapply _)
  }

  def table(tag: Tag) = new FailedContentCheckTable(tag)

  override def deleteCache(model: FailedContentCheck)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: FailedContentCheck)(implicit session: RSession): Unit = {}

  private def sortUrls(url1: String, url2: String): (String, String) = if (url1.compareTo(url2) < 0) (url1, url2) else (url2, url1)

  def getByUrls(url1: String, url2: String)(implicit session: RSession): Option[FailedContentCheck] = {
    val (sorted1, sorted2) = sortUrls(url1, url2)
    val (hash1, hash2) = (UrlHash.hashUrl(sorted1), UrlHash.hashUrl(sorted2))
    (for (r <- rows if (r.url1Hash === hash1 && r.url2Hash === hash2)) yield r).firstOption
  }

  def createOrIncrease(url1: String, url2: String)(implicit session: RWSession): Unit = {
    getByUrls(url1, url2) match {
      case Some(record) => save(record.withCounts(record.counts + 1))
      case None => {
        val (sorted1, sorted2) = sortUrls(url1, url2)
        val (hash1, hash2) = (UrlHash.hashUrl(sorted1), UrlHash.hashUrl(sorted2))
        save(FailedContentCheck(url1Hash = hash1, url2Hash = hash2, url1 = sorted1, url2 = sorted2, counts = 1, lastContentCheck = currentDateTime))
      }
    }
  }

  def contains(url1: String, url2: String)(implicit session: RSession): Boolean = getByUrls(url1, url2).isDefined

  def getRecentCountByURL(url: String, since: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val hash = UrlHash.hashUrl(url).hash
    val q = sql"""select count(*) from failed_content_check where url1_hash = ${hash} or url2_hash = ${hash} and updated_at > ${since}"""
    q.as[Int].list.head
  }
}
