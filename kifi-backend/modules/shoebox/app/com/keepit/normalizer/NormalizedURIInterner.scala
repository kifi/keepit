package com.keepit.normalizer

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import scala.concurrent.ExecutionContext
import scala.util._
import com.keepit.queue.NormalizationUpdateTask
import java.sql.SQLException
import com.keepit.common.logging.{ Timer, Logging }
import org.feijoas.mango.common.cache.CacheBuilder
import java.util.concurrent.TimeUnit
import com.kifi.franz.SQSQueue
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.Left
import com.keepit.model.NormalizedURIUrlHashKey
import scala.util.Failure
import scala.Right
import scala.util.Success

@Singleton
class NormalizedURIInterner @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    urlHashCache: NormalizedURIUrlHashCache,
    updateQueue: SQSQueue[NormalizationUpdateTask],
    urlRepo: URLRepo,
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends Logging {

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
  def internByUri(url: String, candidates: NormalizationCandidate*)(implicit session: RWSession): NormalizedURI = urlLocks.get(url).synchronized {
    log.debug(s"[internByUri($url,candidates:(sz=${candidates.length})${candidates.mkString(",")})]")
    statsd.time(key = "normalizedURIRepo.internByUri", ONE_IN_THOUSAND) { timer =>
      val resUri = getByUriOrPrenormalize(url) match {
        case Success(Left(uri)) =>
          if (candidates.nonEmpty) {
            session.onTransactionSuccess {
              updateQueue.send(NormalizationUpdateTask(uri.id.get, false, candidates))
            }
          }
          statsd.timing("normalizedURIRepo.internByUri.in_db", timer, ONE_IN_THOUSAND)
          uri
        case Success(Right(prenormalizedUrl)) =>
          val normalization = SchemeNormalizer.findSchemeNormalization(prenormalizedUrl)
          urlHashCache.get(NormalizedURIUrlHashKey(NormalizedURI.hashUrl(prenormalizedUrl))) match {
            case Some(cached) =>
              airbrake.notify(s"prenormalizedUrl [$prenormalizedUrl] already in cache [$cached] skipping save")
              statsd.timing("normalizedURIRepo.internByUri.in_cache", timer, ONE_IN_THOUSAND)
              cached
            case None =>
              val candidate = NormalizedURI.withHash(normalizedUrl = prenormalizedUrl, normalization = normalization)
              val newUri = try {
                val saved = normalizedURIRepo.save(candidate)
                statsd.timing("normalizedURIRepo.internByUri.new", timer, ONE_IN_THOUSAND)
                saved
              } catch {
                case sqlException: SQLException =>
                  log.error(s"""error persisting prenormalizedUrl $prenormalizedUrl of url $url with candidates [${candidates.mkString(" ")}]""", sqlException)
                  normalizedURIRepo.deleteCache(candidate)
                  normalizedURIRepo.getByNormalizedUrl(prenormalizedUrl) match {
                    case None =>
                      statsd.timing("normalizedURIRepo.internByUri.new.error.not_recovered", timer, ONE_IN_THOUSAND)
                      throw new UriInternException(s"could not find existing url $candidate in the db", sqlException)
                    case Some(fromDb) =>
                      log.warn(s"recovered url $fromDb from the db via urlHash")
                      //This situation is likely a race condition. In this case we better clear out the cache and let the next call go the the source of truth (the db)
                      statsd.timing("normalizedURIRepo.internByUri.new.error.recovered", timer, ONE_IN_THOUSAND)
                      fromDb
                  }
                case t: Throwable =>
                  throw new UriInternException(s"""error persisting prenormalizedUrl $prenormalizedUrl of url $url with candidates [${candidates.mkString(" ")}]""", t)
              }
              urlRepo.save(URLFactory(url = url, normalizedUriId = newUri.id.get))
              session.onTransactionSuccess {
                updateQueue.send(NormalizationUpdateTask(newUri.id.get, true, candidates))
              }
              statsd.timing("normalizedURIRepo.internByUri.new.url_save", timer, ONE_IN_THOUSAND)
              newUri
          }
        case Failure(ex) =>
          /**
           * if we can't parse a url we should not let it get to the db.
           * its scraping should stop as well and it will be removed from cache.
           * an error should be thrown so we could examine the problem.
           * in most cases its a user error so the exceptions may be caught upper in the stack.
           */
          val uriCandidate = NormalizedURI.withHash(normalizedUrl = url, normalization = None)
          normalizedURIRepo.deleteCache(uriCandidate)
          normalizedURIRepo.getByNormalizedUrl(url) match {
            case None =>
              statsd.timing("normalizedURIRepo.internByUri.fail.not_found", timer, ONE_IN_THOUSAND)
              throw new UriInternException(s"could not parse or find url in db: $url", ex)
            case Some(fromDb) =>
              throw new UriInternException(s"Uri was in the db despite a normalization failure: $fromDb", ex)
          }
      }
      log.debug(s"[internByUri($url)] resUri=$resUri")
      resUri
    }
  }

  def getByUriOrPrenormalize(url: String)(implicit session: RSession, timer: Timer = new Timer()): Try[Either[NormalizedURI, String]] = {
    prenormalize(url).map { prenormalizedUrl =>
      log.debug(s"using prenormalizedUrl $prenormalizedUrl for url $url")
      val normalizedUri = normalizedURIRepo.getByNormalizedUrl(prenormalizedUrl) map {
        case uri if uri.state == NormalizedURIStates.REDIRECTED =>
          val nuri = normalizedURIRepo.get(uri.redirect.get)
          statsd.timing(key = "normalizedURIRepo.getByUriOrPrenormalize.redirected", timer, ONE_IN_THOUSAND)
          log.info(s"following a redirection path for $url on uri $nuri")
          if (uri.normalization == Some(Normalization.MOVED) && uri.url.contains("kifi.com")) {
            airbrake.notify(s"Permanent redirect on kifi.com may no longer be valid: ${uri.id.get}: ${uri.url} to ${nuri.id.get}: ${nuri.url}.")
          }
          nuri
        case uri =>
          statsd.timing(key = "normalizedURIRepo.getByUriOrPrenormalize.not_redirected", timer, ONE_IN_THOUSAND)
          uri
      }
      log.debug(s"[getByUriOrPrenormalize($url)] located normalized uri $normalizedUri for prenormalizedUrl $prenormalizedUrl")
      normalizedUri.map(Left.apply).getOrElse(Right(prenormalizedUrl))
    }
  }

  def normalize(uriString: String)(implicit session: RSession): Try[String] = statsd.time(key = "normalizedURIRepo.normalize", ONE_IN_THOUSAND) { implicit timer =>
    getByUri(uriString).map(uri => Success(uri.url)) getOrElse prenormalize(uriString)
  }

  private def prenormalize(uriString: String)(implicit timer: Timer): Try[String] = {
    val prenormalized = normalizationService.prenormalize(uriString)
    statsd.timing(key = "normalizedURIRepo.prenormalize", timer, ONE_IN_THOUSAND)
    prenormalized
  }

  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    getByUriOrPrenormalize(url).map(_.left.toOption).toOption.flatten
  }

}
