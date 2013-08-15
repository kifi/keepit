package com.keepit.model

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{State, Id, SequenceNumber}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.normalizer.{NormalizationService, NormalizationCandidate}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] {
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getScraped(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI
}

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  urlHashCache: NormalizedURIUrlHashCache,
  scrapeRepoProvider: Provider[ScrapeInfoRepo],
  normalizedURIFactory: NormalizedURIFactory)
  extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  private val sequence = db.getSequence("normalized_uri_sequence")

  override val table = new RepoTable[NormalizedURI](db, "normalized_uri") with ExternalIdColumn[NormalizedURI] {
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def screenshotUpdatedAt = column[DateTime]("screenshot_updated_at")
    def normalization = column[Normalization]("normalization")
    def redirect = column[Id[NormalizedURI]]("redirect")
    def redirectTime = column[DateTime]("redirect_time")
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title.? ~ url ~ urlHash ~ state ~ seq ~
        screenshotUpdatedAt.? ~ normalization.? ~ redirect.? ~ redirectTime.? <> (NormalizedURI.apply _, NormalizedURI.unapply _)
  }

  def getIndexable(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val q = (for (f <- table if f.seq > sequenceNumber) yield f).sortBy(_.seq)
    (if (limit >= 0) q.take(limit) else q).list
  }

  def getScraped(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val q = (for (f <- table if (f.seq > sequenceNumber && f.state === NormalizedURIStates.SCRAPED)) yield f).sortBy(_.seq)
    (if (limit >= 0) q.take(limit) else q).list
  }

  override def invalidateCache(uri: NormalizedURI)(implicit session: RSession) = {
    uri.id map {id => idCache.set(NormalizedURIKey(id), uri)}
    urlHashCache.set(NormalizedURIUrlHashKey(NormalizedURI.hashUrl(uri.url)), uri)
    uri
  }

  override def get(id: Id[NormalizedURI])(implicit session: RSession): NormalizedURI = {
    idCache.getOrElse(NormalizedURIKey(id)) {
      (for(f <- table if f.id is id) yield f).first
    }
  }

  def allActive()(implicit session: RSession): Seq[NormalizedURI] =
    (for(f <- table if f.state === NormalizedURIStates.ACTIVE) yield f).list

  override def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI = {
    val num = sequence.incrementAndGet()
    val saved = super.save(uri.copy(seq = num))

    lazy val scrapeRepo = scrapeRepoProvider.get
    if (uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.ACTIVE) {
      // If uri.state is ACTIVE or INACTIVE, we do not want an ACTIVE ScrapeInfo record for it
      scrapeRepo.getByUri(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.ACTIVE =>
          scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.INACTIVE))
        case _ => // do nothing
      }
    } else {
      // Otherwise, ensure that ScrapeInfo has an active record for it.
      scrapeRepo.getByUri(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.INACTIVE =>
          scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.ACTIVE))
        case Some(scrapeInfo) => // do nothing
        case None =>
          scrapeRepo.save(ScrapeInfo(uriId = saved.id.get))
      }
    }

    saved
  }

  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI] = {
    val limited = {
      val q = (for (t <- table if t.state === state) yield t)
      limit match {
        case some if some > 0 => q.take(some)
        case _ => q
      }
    }
    limited.list
  }

  private def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI] = {
    val hash = NormalizedURI.hashUrl(normalizedUrl)
    urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(hash)) {
      (for (t <- table if t.urlHash === hash) yield t).firstOption
    }
  }

  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    val normalizedUrl = normalizedURIFactory.normalize(url)
    getByNormalizedUrl(normalizedUrl)
  }

  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI = {
    val normalizedUrl = normalizedURIFactory.normalize(url)
    val normalizedUri = getByNormalizedUrl(normalizedUrl) match {
      case Some(uri)=> uri
      case None => {
        val newUri = NormalizedURI.withHash(normalizedUrl = normalizedUrl)
        newUri.urlHash.hash.intern.synchronized {
          getByNormalizedUrl(normalizedUrl) match {
            case Some(uri) => uri
            case None => save(newUri)
          }
        }
      }
    }
    normalizedURIFactory.normalizationService.update(normalizedUri, candidates:_*)(this)
    normalizedUri
  }
}

@Singleton
case class NormalizedURIFactory @Inject() (normalizationService: NormalizationService) {

  def normalize(url: String)(implicit session: RSession) = normalizationService.normalize(url)

  def apply(url: String)(implicit session: RSession): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURIStates.ACTIVE, normalization = None)

  def apply(url: String, state: State[NormalizedURI])(implicit session: RSession): NormalizedURI =
    apply(title = None, url = url, state = state, normalization = None)

  def apply(title: String, url: String)(implicit session: RSession): NormalizedURI =
    apply(title = Some(title), url = url, state = NormalizedURIStates.ACTIVE, normalization = None)

  def apply(title: String, url: String, state: State[NormalizedURI])(implicit session: RSession): NormalizedURI =
    apply(title = Some(title), url = url, state = state, normalization = None)

  def apply(url: String, normalization: Normalization)(implicit session: RSession): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURIStates.ACTIVE, normalization = Some(normalization))

  def apply(url: String, title: Option[String] = None, state: State[NormalizedURI] = NormalizedURIStates.ACTIVE, normalization: Option[Normalization] = None)(implicit session: RSession): NormalizedURI =
    NormalizedURI.withHash(normalizedUrl = normalize(url), title = title, state = state, normalization = normalization)
}
