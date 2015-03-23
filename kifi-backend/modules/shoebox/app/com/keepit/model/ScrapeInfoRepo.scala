package com.keepit.model

import com.google.inject.{ Provider, Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.Id
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.slick.jdbc.{ StaticQuery => Q }
import Q.interpolation

@ImplementedBy(classOf[ScrapeInfoRepoImpl])
trait ScrapeInfoRepo extends Repo[ScrapeInfo] {
  def allActive(implicit session: RSession): Seq[ScrapeInfo]
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo]
  def getActiveByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo]
  def getOverdueCount(due: DateTime = currentDateTime)(implicit session: RSession): Int
  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo]
  def getAssignedCount(due: DateTime = currentDateTime)(implicit session: RSession): Int
  def getAssignedList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo]
  def setForRescrapeByRegex(urlRegex: String, withinMinutes: Int)(implicit session: RSession): Int
  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit
}

@Singleton
class ScrapeInfoRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val normUriRepo: Provider[NormalizedURIRepoImpl])
    extends DbRepo[ScrapeInfo] with ScrapeInfoRepo {

  import db.Driver.simple._

  type RepoImpl = ScrapeInfoRepoTable
  class ScrapeInfoRepoTable(tag: Tag) extends RepoTable[ScrapeInfo](db, tag, "scrape_info") {
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def workerId = column[Id[ScraperWorker]]("worker_id")
    def lastScrape = column[DateTime]("last_scrape", O.NotNull)
    def nextScrape = column[DateTime]("next_scrape", O.NotNull)
    def interval = column[Double]("scrape_interval", O.NotNull)
    def failures = column[Int]("failures", O.NotNull)
    def signature = column[String]("signature", O.NotNull)
    def destinationUrl = column[String]("destination_url", O.Nullable)
    def seq = column[Int]("seq", O.NotNull)
    def * = (id.?, uriId, workerId.?, lastScrape, nextScrape, interval, failures, state, signature, destinationUrl.?) <> ((ScrapeInfo.apply _).tupled, ScrapeInfo.unapply _)
  }

  def table(tag: Tag) = new ScrapeInfoRepoTable(tag)
  initTable()

  override def deleteCache(model: ScrapeInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: ScrapeInfo)(implicit session: RSession): Unit = {}

  def allActive(implicit session: RSession): Seq[ScrapeInfo] = {
    val repo = normUriRepo.get
    import repo.db.Driver.simple._
    (for {
      s <- rows
      u <- repo.rows if (s.uriId === u.id)
    } yield s).list
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo] =
    (for (f <- rows if f.uriId === uriId) yield f).firstOption

  def getActiveByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Option[ScrapeInfo] =
    (for (f <- rows if f.uriId === uriId && f.state === ScrapeInfoStates.ACTIVE) yield f).firstOption

  def getOverdueCount(due: DateTime = currentDateTime)(implicit session: RSession): Int = {
    sql"select count(*) from scrape_info where state = '#${ScrapeInfoStates.ACTIVE.value}' and next_scrape < $due".as[Int].first
  }

  def getOverdueList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo] = {
    val q = (for (f <- rows if f.nextScrape <= due && f.state === ScrapeInfoStates.ACTIVE) yield f).sortBy(_.nextScrape)
    (if (limit >= 0) q.take(limit) else q).list
  }

  def getAssignedCount(due: DateTime = currentDateTime)(implicit session: RSession): Int = {
    sql"select count(*) from scrape_info where state = '#${ScrapeInfoStates.ASSIGNED.value}' and next_scrape < $due".as[Int].first
  }

  def getAssignedList(limit: Int = -1, due: DateTime = currentDateTime)(implicit session: RSession): Seq[ScrapeInfo] = {
    val q = (for (f <- rows if f.nextScrape <= due && f.state === ScrapeInfoStates.ASSIGNED) yield f)
    (if (limit >= 0) q.take(limit) else q).list
  }

  def setForRescrapeByRegex(urlRegex: String, withinMinutes: Int)(implicit session: RSession): Int = {
    val now = currentDateTime
    val withinSeconds = withinMinutes * 60
    var updateCount = 0
    (for {
      u <- normUriRepo.get.rows if u.url like urlRegex
      s <- rows if (u.id === s.uriId) && (s.state === ScrapeInfoStates.ACTIVE)
    } yield s
    ).mutate { r =>
      r.row = r.row.copy(nextScrape = now.plusSeconds((scala.math.random * withinSeconds).toInt), signature = "")
      updateCount += 1
    }
    updateCount

  }

  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {
    val uriId = uri.id.get
    if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(uri.state)) {
      val info = getByUriId(uriId)
      val toSave = info match {
        case Some(s) => s.state match {
          case ScrapeInfoStates.ACTIVE => s.withNextScrape(date)
          case ScrapeInfoStates.ASSIGNED => s // no change
          case ScrapeInfoStates.INACTIVE => {
            log.warn(s"[scheduleScrape(${uri.toShortString})] scheduling INACTIVE $s")
            s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(date) // todo(Ray): dangerous; revisit
          }
        }
        case None => ScrapeInfo(uriId = uriId, nextScrape = date)
      }
      val saved = save(toSave)
      log.info(s"[scheduleScrape] scheduled for ${uri.toShortString}; saved=$saved")
      // todo(Ray): It may be nice to force trigger a scrape directly
    }
  }
}

