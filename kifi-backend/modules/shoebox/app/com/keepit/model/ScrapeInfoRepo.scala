package com.keepit.model

import com.google.inject.{Provider, Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.slick.jdbc.{StaticQuery => Q}

@ImplementedBy(classOf[ScrapeInfoRepoImpl])
trait ScrapeInfoRepo extends Repo[ScrapeInfo] {
  def allActive(implicit session: RSession): Seq[ScrapeInfo]
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo]
  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo]
  def getPendingCount()(implicit session: RSession):Int
  def getPendingList()(implicit session: RSession):Seq[ScrapeInfo]
  def getOverduePendingList(due: DateTime = currentDateTime)(implicit session: RSession):Seq[ScrapeInfo]
  def setForRescrapeByRegex(urlRegex: String, withinMinutes: Int)(implicit session: RSession): Int
}

@Singleton
class ScrapeInfoRepoImpl @Inject() (
                                     val db: DataBaseComponent,
                                     val clock: Clock,
                                     val normUriRepo: Provider[NormalizedURIRepoImpl])
  extends DbRepo[ScrapeInfo] with ScrapeInfoRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[ScrapeInfo](db, "scrape_info") {
    def uriId =      column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def lastScrape = column[DateTime]("last_scrape", O.NotNull)
    def nextScrape = column[DateTime]("next_scrape", O.NotNull)
    def interval =   column[Double]("scrape_interval", O.NotNull)
    def failures =   column[Int]("failures", O.NotNull)
    def signature =  column[String]("signature", O.NotNull)
    def destinationUrl = column[String]("destination_url", O.Nullable)
    def seq = column[Int]("seq", O.NotNull)
    def * = id.? ~ uriId ~ lastScrape ~ nextScrape ~ interval ~ failures ~ state ~ signature ~ destinationUrl.? <> (ScrapeInfo.apply _, ScrapeInfo.unapply _)
  }

  override def deleteCache(model: ScrapeInfo)(implicit session: RSession): Unit = {}

  def allActive(implicit session: RSession): Seq[ScrapeInfo] = {
    (for {
      (s, u) <- table innerJoin normUriRepo.get.table on (_.uriId is _.id)
    } yield s.*).list
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo] =
    (for(f <- table if f.uriId === uriId) yield f).firstOption

  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo] = {
    val q = (for(f <- table if f.nextScrape <= due && f.state === ScrapeInfoStates.ACTIVE) yield f).sortBy(_.nextScrape)
    (if (limit > 0) q.take(limit) else q).list
  }

  def getPendingCount()(implicit session: RSession):Int = {
    Q.queryNA[Int](s"select count(*) from scrape_info where state = 'PENDING'").first
  }

  def getPendingList()(implicit session: RSession):Seq[ScrapeInfo] = {
    (for(f <- table if f.state === ScrapeInfoStates.PENDING) yield f).list
  }

  def getOverduePendingList(due: DateTime = currentDateTime)(implicit session: RSession):Seq[ScrapeInfo] = {
    (for(f <- table if f.state === ScrapeInfoStates.PENDING && f.nextScrape <= due) yield f).sortBy(_.nextScrape).list  // TODO: add schedule time or updatedAt
  }

  def setForRescrapeByRegex(urlRegex: String, withinMinutes: Int)(implicit session: RSession): Int = {
    val now = currentDateTime
    val withinSeconds = withinMinutes * 60
    var updateCount = 0
    (for {
      u <- normUriRepo.get.table if u.url like urlRegex
      s <- table if (u.id === s.uriId) && (s.state is ScrapeInfoStates.ACTIVE)
    } yield s
      ).mutate {r =>
      r.row = r.row.copy(nextScrape = now.plusSeconds((scala.math.random * withinSeconds).toInt), signature = "")
      updateCount += 1
    }
    updateCount


  }

}