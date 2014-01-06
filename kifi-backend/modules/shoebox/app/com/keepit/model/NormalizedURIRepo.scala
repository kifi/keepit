package com.keepit.model

import com.google.inject.{ImplementedBy, Provider, Inject, Singleton}
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.common.db.{State, Id, SequenceNumber}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging

import org.joda.time.DateTime
import com.keepit.normalizer.{SchemeNormalizer, NormalizationService, NormalizationCandidate}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.statsd.api.Statsd
import com.google.common.collect.MapMaker
import com.google.common.cache.{CacheLoader, CacheBuilder}
import java.util.concurrent.TimeUnit

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] {
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getScraped(sequenceNumber: SequenceNumber, limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI]
  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI]
  def getByUriOrPrenormalize(url: String)(implicit session: RSession): Either[NormalizedURI, String]
  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI]
  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI
}

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  urlHashCache: NormalizedURIUrlHashCache,
  scrapeRepoProvider: Provider[ScrapeInfoRepo],
  normalizationServiceProvider: Provider[NormalizationService],
  urlRepoProvider: Provider[URLRepo])
extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] with Logging {
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
    def restriction = column[Restriction]("restriction", O.Nullable)
    def normalization = column[Normalization]("normalization", O.Nullable)
    def redirect = column[Id[NormalizedURI]]("redirect", O.Nullable)
    def redirectTime = column[DateTime]("redirect_time", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ title.? ~ url ~ urlHash ~ state ~ seq ~
        screenshotUpdatedAt.? ~ restriction.? ~ normalization.? ~ redirect.? ~ redirectTime.? <> (NormalizedURI.apply _, NormalizedURI.unapply _)
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
    val uriWithSeq = uri.copy(seq = num)
    val saved = super.save(uriWithSeq.clean())

    lazy val scrapeRepo = scrapeRepoProvider.get
    if (uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.ACTIVE || uri.state == NormalizedURIStates.REDIRECTED) {
      // If uri.state is ACTIVE or INACTIVE, we do not want an ACTIVE ScrapeInfo record for it
      scrapeRepo.getByUriId(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.ACTIVE =>
          scrapeRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
        case _ => // do nothing
      }
    } else {
      // Otherwise, ensure that ScrapeInfo has an active record for it.
      scrapeRepo.getByUriId(saved.id.get) match {
        case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.INACTIVE =>
          scrapeRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.ACTIVE))
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

  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI] = {
    Statsd.time(key = "normalizedURIRepo.getByNormalizedUrl") {
      val hash = NormalizedURI.hashUrl(normalizedUrl)
      urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(hash)) {
        (for (t <- table if t.urlHash === hash) yield t).firstOption
      }
    }
  }

  //using readonly db when exist, don't use cache
  def getByUriOrPrenormalize(url: String)(implicit session: RSession): Either[NormalizedURI, String] = {
    val prenormalizedUrl = prenormalize(url)
    log.debug(s"using prenormalizedUrl $prenormalizedUrl for url $url")
    val normalizedUri = getByNormalizedUrl(prenormalizedUrl) map {
        case uri if uri.state == NormalizedURIStates.REDIRECTED => get(uri.redirect.get)
        case uri => uri
      }
    log.debug(s"located normalized uri $normalizedUri for prenormalizedUrl $prenormalizedUrl")
    normalizedUri.map(Left.apply).getOrElse(Right(prenormalizedUrl))
  }


  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    Statsd.time(key = "normalizedURIRepo.getByUri") {
      getByUriOrPrenormalize(url: String).left.toOption
    }
  }

  private val urlLocks = new CacheBuilder().newBuilder().maximumSize(10000).expireAfterWrite(30, TimeUnit.MINUTES).build(
      new CacheLoader[String, Any]() {
        def load(key: String): Any = new String(key)
      });

  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI = {
    Statsd.time(key = "normalizedURIRepo.internByUri") {
      getByUriOrPrenormalize(url) match {
        case Left(uri) => session.onTransactionSuccess(normalizationServiceProvider.get.update(uri, isNew = false, candidates)); uri
        case Right(prenormalizedUrl) => {
          val normalization = findNormalization(prenormalizedUrl)
          val newUri = save(NormalizedURI.withHash(normalizedUrl = prenormalizedUrl, normalization = normalization))
          urlRepoProvider.get.save(URLFactory(url = url, normalizedUriId = newUri.id.get))
          session.onTransactionSuccess(normalizationServiceProvider.get.update(newUri, isNew = true, candidates))
          newUri
        }
      }
    }
  }

  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI] = {
    (for(t <- table if t.state === NormalizedURIStates.REDIRECTED && t.redirect === redirect) yield t).list
  }

  private def prenormalize(uriString: String)(implicit session: RSession): String = Statsd.time(key = "normalizedURIRepo.prenormalize") {
    normalizationServiceProvider.get.prenormalize(uriString)
  }

  private def findNormalization(normalizedUrl: String): Option[Normalization] =
    SchemeNormalizer.generateVariations(normalizedUrl).find { case (_, url) => (url == normalizedUrl) }.map { case (normalization, _) => normalization }
}
