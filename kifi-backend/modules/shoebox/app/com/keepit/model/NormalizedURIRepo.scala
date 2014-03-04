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
import play.modules.statsd.api.Statsd
import org.feijoas.mango.common.cache._
import java.util.concurrent.TimeUnit
import NormalizedURIStates._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.normalizer.NormalizationReference
import scala.slick.lifted.Tag
import com.keepit.common.db.{Model, Id}
import com.keepit.queue._
import play.api.libs.json._
import play.api.Play._
import play.api.Play
import com.keepit.common.aws.AwsConfig

@ImplementedBy(classOf[NormalizedURIRepoImpl])
trait NormalizedURIRepo extends DbRepo[NormalizedURI] with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberFunction[NormalizedURI]{
  def allActive()(implicit session: RSession): Seq[NormalizedURI]
  def getByState(state: State[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getIndexable(sequenceNumber: SequenceNumber[NormalizedURI], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getChanged(sequenceNumber: SequenceNumber[NormalizedURI], includeStates: Set[State[NormalizedURI]], limit: Int = -1)(implicit session: RSession): Seq[NormalizedURI]
  def getCurrentSeqNum()(implicit session: RSession): SequenceNumber[NormalizedURI]
  def getByNormalizedUrl(normalizedUrl: String)(implicit session: RSession): Option[NormalizedURI]
  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI]
  def getByUriOrPrenormalize(url: String)(implicit session: RSession): Either[NormalizedURI, String]
  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI]
  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI
  def save(uri: NormalizedURI)(implicit session: RWSession): NormalizedURI
  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI]
  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession): Unit
  }

@Singleton
class NormalizedURIRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  idCache: NormalizedURICache,
  urlHashCache: NormalizedURIUrlHashCache,
  scrapeRepoProvider: Provider[ScrapeInfoRepo],
  normalizationServiceProvider: Provider[NormalizationService],
  urlRepoProvider: Provider[URLRepo],
  taskQ:NormalizationUpdateJobQueue,
  airbrake: AirbrakeNotifier,
  awsConfig: AwsConfig)
extends DbRepo[NormalizedURI] with NormalizedURIRepo with ExternalIdColumnDbFunction[NormalizedURI] with SeqNumberDbFunction[NormalizedURI] with Logging {

  import db.Driver.simple._


  private val sequence = db.getSequence[NormalizedURI]("normalized_uri_sequence")

  type RepoImpl = NormalizedURITable
  class NormalizedURITable(tag: Tag) extends RepoTable[NormalizedURI](db, tag, "normalized_uri") with ExternalIdColumn[NormalizedURI] with SeqNumberColumn[NormalizedURI] {
    def title = column[String]("title")
    def url = column[String]("url", O.NotNull)
    def urlHash = column[UrlHash]("url_hash", O.NotNull)
    def screenshotUpdatedAt = column[DateTime]("screenshot_updated_at")
    def restriction = column[Restriction]("restriction", O.Nullable)
    def normalization = column[Normalization]("normalization", O.Nullable)
    def redirect = column[Id[NormalizedURI]]("redirect", O.Nullable)
    def redirectTime = column[DateTime]("redirect_time", O.Nullable)
    def * = (id.?,createdAt,updatedAt,externalId,title.?,url,urlHash,state,seq,screenshotUpdatedAt.?,restriction.?,normalization.?,redirect.?,redirectTime.?) <> ((NormalizedURI.apply _).tupled, NormalizedURI.unapply _)
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
    val num = sequence.incrementAndGet()
    val uriWithSeq = uri.copy(seq = num)

    val validatedUri = if ( uri.state != NormalizedURIStates.REDIRECTED && (uri.redirect.isDefined || uri.redirectTime.isDefined) ){
      airbrake.notify(s"uri ${uri.id} had redirection info. We are trying to save it with state ${uri.state}. Going to save state as redirected.")
      uriWithSeq.copy(state = NormalizedURIStates.REDIRECTED)
    } else uriWithSeq

    val saved = super.save(validatedUri.clean())

    // todo: move out the logic modifying scrapeInfo table
    lazy val scrapeRepo = scrapeRepoProvider.get
    uri.state match {
      case e:State[NormalizedURI] if DO_NOT_SCRAPE.contains(e) => // ensure no ACTIVE scrapeInfo records
        scrapeRepo.getByUriId(saved.id.get) match {
          case Some(scrapeInfo) if scrapeInfo.state == ScrapeInfoStates.ACTIVE =>
            scrapeRepo.save(scrapeInfo.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
          case _ => // do nothing
        }
      case SCRAPE_FAILED | SCRAPED =>
        scrapeRepo.getByUriId(saved.id.get) match { // do NOT use saveStateAndNextScrape
          case Some(scrapeInfo) if (scrapeInfo.state == ScrapeInfoStates.INACTIVE) =>
            scrapeRepo.save(scrapeInfo.withState(ScrapeInfoStates.ACTIVE))
          case _ => // do nothing
        }
      case SCRAPE_WANTED => // do nothing
      case _ =>
        throw new IllegalStateException(s"Unhandled state=${uri.state}; uri=$uri")
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
    Statsd.time(key = "normalizedURIRepo.getByNormalizedUrl") {
      val hash = NormalizedURI.hashUrl(normalizedUrl)
      urlHashCache.getOrElseOpt(NormalizedURIUrlHashKey(hash)) {
        (for (t <- rows if t.urlHash === hash) yield t).firstOption
      }
    }
  }

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

  /**
   * if a stack trace will dump the lock we'll at least know what it belongs to
   */
  private def newUrlLock = (str: String) => new String(str)

  /**
   * We don't want to aggregate locks for ever, its no likely that a lock is still locked after one second
   */
  private val urlLocks = CacheBuilder.newBuilder().maximumSize(10000).weakKeys().expireAfterWrite(30, TimeUnit.MINUTES).build(newUrlLock)

  /**
   * Locking since there may be few calls coming at the same time from the client with the same url (e.g. get page info, and record visited).
   * The lock is on the exact same url and using intern so we can have a globaly unique object of the url.
   * Possible downside is that the permgen will fill up with these urls
   *
   * todo(eishay): use RequestConsolidator on a controller level that calls the repo level instead of locking.
   */
  val sqsEnable:Boolean = awsConfig.sqsEnabled // todo(ray):removeme
  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI = urlLocks.get(url).synchronized {
    log.info(s"[internByUri($url,candidates:(sz=${candidates.length})${candidates.mkString(",")})]")
    Statsd.time(key = "normalizedURIRepo.internByUri") {
      getByUriOrPrenormalize(url) match {
        case Left(uri) =>
          session.onTransactionSuccess {
            if (sqsEnable)
              taskQ.sendTask(NormalizationUpdateTask(uri.id.get, false, candidates))
            else
              normalizationServiceProvider.get.update(NormalizationReference(uri, isNew = false), candidates: _*)
          }
          uri
        case Right(prenormalizedUrl) => {
          val normalization = SchemeNormalizer.findSchemeNormalization(prenormalizedUrl)
          val newUri = save(NormalizedURI.withHash(normalizedUrl = prenormalizedUrl, normalization = normalization))
          urlRepoProvider.get.save(URLFactory(url = url, normalizedUriId = newUri.id.get))
          session.onTransactionSuccess{
            if (sqsEnable)
              taskQ.sendTask(NormalizationUpdateTask(newUri.id.get, true, candidates))
            else
              normalizationServiceProvider.get.update(NormalizationReference(newUri, isNew = true), candidates: _*)
          }
          newUri
        }
      }
    }
  }

  def toBeRemigrated()(implicit session: RSession): Seq[NormalizedURI] =
    (for(t <- rows if t.state =!= NormalizedURIStates.REDIRECTED && t.redirect.isNotNull) yield t).list

  def getByRedirection(redirect: Id[NormalizedURI])(implicit session: RSession): Seq[NormalizedURI] = {
    (for(t <- rows if t.state === NormalizedURIStates.REDIRECTED && t.redirect === redirect) yield t).list
  }

  private def prenormalize(uriString: String)(implicit session: RSession): String = Statsd.time(key = "normalizedURIRepo.prenormalize") {
    normalizationServiceProvider.get.prenormalize(uriString)
  }

  def updateURIRestriction(id: Id[NormalizedURI], r: Option[Restriction])(implicit session: RWSession) = {
    val q = for {t <- rows if t.id === id} yield t.restriction
    q.update(r.getOrElse(null))
    invalidateCache(get(id))
  }
}
