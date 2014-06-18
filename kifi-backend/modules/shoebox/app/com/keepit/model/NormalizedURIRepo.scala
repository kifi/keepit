package com.keepit.model

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.db.{State, SequenceNumber}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging
import org.joda.time.DateTime
import com.keepit.normalizer._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.feijoas.mango.common.cache._
import NormalizedURIStates._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.Id
import com.keepit.queue._
import scala.slick.jdbc.StaticQuery
import com.keepit.model.serialize.UriIdAndSeq

class UriInternException(msg: String, cause: Throwable) extends Exception(msg, cause)

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberFunction[NormalizedURI]{
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getChanged(sequenceNumber: SequenceNumber[NormalizedURI], includeStates: Set[State[NormalizedURI]], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIdAndSeqChanged(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[UriIdAndSeq]
  def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[NormalizedURI]
  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI]
  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI]
  def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI
  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI]
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession): Unit
  def updateScreenshotUpdatedAt(id: Id[NormalizedURI], time: DateTime)(implicit session: RWSession): Unit
  def getRestrictedURIs(targetRestriction: Restriction)(implicit session: RSession): Seq[NormalizedURI]
}

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  urlHashCache: NormalizedURIUrlHashCache,
  scrapeRepoProvider: Provider[ScrapeInfoRepo],
  airbrake: AirbrakeNotifier)
extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberDbFunction[NormalizedURI] with Logging {

  import db.Driver.simple._

  private val sequence = db.getSequence[NormalizedURI]("normalized_uri_sequence")

  type RepoImpl = NormalizedURITable
  class NormalizedURITable(tag: Tag) extends RepoTable[NormalizedURI](db, tag, "normalized_uri") with ExternalIdColumn[NormalizedURI] with SeqNumberColumn[NormalizedURI] {
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def screenshotUpdatedAt = column[DateTime]("screenshot_updated_at")
    def restriction = column[Option[Restriction]]("restriction")
    def normalization = column[Normalization]("normalization", O.Nullable)
    def redirect = column[Id[NormalizedURI]]("redirect", O.Nullable)
    def redirectTime = column[DateTime]("redirect_time", O.Nullable)
    def * = (id.?,createdAt,updatedAt,externalId,title.?,url,urlHash,state,seq,screenshotUpdatedAt.?,restriction, normalization.?,redirect.?,redirectTime.?) <> ((NormalizedURI.apply _).tupled, NormalizedURI.unapply _)
  }

  def table(tag:Tag) = new NormalizedURITable(tag)
  initTable()

  def getIndexable(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    super.getBySequenceNumber(sequenceNumber, limit)
  }

  def getChanged(sequenceNumber: SequenceNumber[NormalizedURI], states: Set[State[NormalizedURI]], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val q = (for (f <- rows if (f.seq > sequenceNumber && f.state.inSet(states))) yield f).sortBy(_.seq)
    (if (limit >= 0) q.take(limit) else q).list
  }

  def getIdAndSeqChanged(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[UriIdAndSeq] = {
    import StaticQuery.interpolation
    sql"""
      select id, seq from normalized_uri
      where seq > ${sequenceNumber.value}
            and state in ('#${NormalizedURIStates.SCRAPED.value}', '#${NormalizedURIStates.SCRAPE_FAILED.value}', '#${NormalizedURIStates.UNSCRAPABLE.value}')
      limit $limit""".as[(Long, Long)].list.map {
        case (id, seq) => UriIdAndSeq(Id[NormalizedURI](id), SequenceNumber[NormalizedURI](seq))
      }.sortBy(_.seq)
  }

  override def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[NormalizedURI] = {
    sequence.getLastGeneratedSeq()
  }

  override def invalidateCache(uri: NormalizedURI)(implicit session: RSession): Unit = {
    if (uri.state == NormalizedURIStates.INACTIVE){
      deleteCache(uri)
    } else{
      uri.id map {id => idCache.set(NormalizedURIKey(id), uri)}
      urlHashCache.set(NormalizedURIUrlHashKey(NormalizedURI.hashUrl(uri.url)), uri)
    }
  }

  override def deleteCache(uri: NormalizedURI)(implicit session: RSession): Unit = {
    uri.id map {id => idCache.remove(NormalizedURIKey(id))}
    urlHashCache.remove(NormalizedURIUrlHashKey(NormalizedURI.hashUrl(uri.url)))
  }

  override def get(id: Id[NormalizedURI])(implicit session: RSession): NormalizedURI = {
    idCache.getOrElse(NormalizedURIKey(id)) {
      (for(f <- rows if f.id is id) yield f).first
    }
  }

  def allActive()(implicit session: RSession): Seq[NormalizedURI] =
    (for(f <- rows if f.state === NormalizedURIStates.ACTIVE) yield f).list

  override def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI = {
    log.info(s"about to persist $uri")

    // setting a negative sequence number for deferred assignment
    val num = SequenceNumber[NormalizedURI](clock.now.getMillis() - Long.MaxValue)
    val uriWithSeq = uri.copy(seq = num)

    val validatedUri = if ( uri.state != NormalizedURIStates.REDIRECTED && (uri.redirect.isDefined || uri.redirectTime.isDefined) ){
      airbrake.notify(s"uri ${uri.id} had redirection info. We are trying to save it with state ${uri.state}. Going to save state as redirected.")
      uriWithSeq.copy(state = NormalizedURIStates.REDIRECTED)
    } else uriWithSeq

    val cleaned = validatedUri.clean()
    val saved = try {
      super.save(cleaned)
    } catch {
      case e: Throwable =>
        deleteCache(cleaned)
        throw e
    }

    // todo: move out the logic modifying scrapeInfo table
    lazy val scrapeRepo = scrapeRepoProvider.get
    saved.state match {
      case e:State[NormalizedURI] if DO_NOT_SCRAPE.contains(e) => // ensure no ACTIVE scrapeInfo records
        scrapeRepo.getByUriId(saved.id.get) match {
          case Some(scrapeInfo) if scrapeInfo.state != ScrapeInfoStates.INACTIVE =>
            val savedSI = scrapeRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
            log.info(s"[save(${saved.toShortString})] mark scrapeInfo as INACTIVE; si=$savedSI")
          case _ => // do nothing
        }
      case SCRAPE_FAILED | SCRAPED =>
        scrapeRepo.getByUriId(saved.id.get) match { // do NOT use saveStateAndNextScrape
          case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.INACTIVE =>
            val savedSI = scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.ACTIVE))
            log.info(s"[save(${saved.toShortString})] mark scrapeInfo as ACTIVE; si=$savedSI")
          case _ => // do nothing
        }
      case ACTIVE => // do nothing
      case _ =>
        throw new IllegalStateException(s"Unhandled state=${saved.state}; uri=$uri")
    }
    saved
  }

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val limited = {
      val q = (for (t <- rows if t.state === state) yield t)
      limit match {
        case some if some > 0 => q.take(some)
        case _ => q
      }
    }
    limited.list
  }

  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI] = {
    statsd.time(key = "normalizedURIRepo.getByNormalizedUrl", ONE_IN_HUNDRED) { timer =>
      val hash = NormalizedURI.hashUrl(normalizedUrl)
      urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(hash)) {
        (for (t <- rows if t.urlHash === hash) yield t).firstOption
      }
    }
  }

  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI] =
    (for(t <- rows if t.state =!= NormalizedURIStates.REDIRECTED && t.redirect.isNotNull) yield t).list

  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI] = {
    (for(t <- rows if t.state === NormalizedURIStates.REDIRECTED && t.redirect === redirect) yield t).list
  }

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession) = {
    val q = for {t <- rows if t.id === id} yield (t.restriction, t.seq)
    val newSeq = sequence.incrementAndGet()
    q.update(r, newSeq)
    invalidateCache(get(id).copy(restriction = r, seq = newSeq))
  }

  def updateScreenshotUpdatedAt(id: Id[NormalizedURI], time: DateTime)(implicit session: RWSession) = {
    val updateTime = clock.now
    (for {t <- rows if t.id === id} yield (t.updatedAt, t.screenshotUpdatedAt)).update((updateTime, time))
    invalidateCache(get(id).copy(updatedAt = updateTime, screenshotUpdatedAt = Some(time)))
  }

  def getRestrictedURIs(targetRestriction: Restriction)(implicit session: RSession): Seq[NormalizedURI] = {
    {for( r <- rows if r.restriction === targetRestriction) yield r}.list
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "normalized_uri", limit)
  }
}
