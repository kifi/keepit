package com.keepit.normalizer

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import scala.concurrent.ExecutionContext
import scala.util._
import com.keepit.queue.NormalizationUpdateTask
import com.keepit.common.logging.{ Timer, Logging }
import com.kifi.franz.SQSQueue
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.Left
import scala.util.Failure
import scala.Right
import scala.util.Success

@Singleton
class NormalizedURIInterner @Inject() (
    db: Database,
    normalizedURIRepo: NormalizedURIRepo,
    updateQueue: SQSQueue[NormalizationUpdateTask],
    urlRepo: URLRepo,
    normalizationService: NormalizationService,
    implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends Logging {

  def internByUri(url: String, contentWanted: Boolean = false, candidates: Set[NormalizationCandidate] = Set.empty)(implicit session: RWSession): NormalizedURI = {
    log.debug(s"[internByUri($url,candidates:(sz=${candidates.size})${candidates.mkString(",")})]")
    statsd.time(key = "normalizedURIRepo.internByUri", ONE_IN_THOUSAND) { timer =>
      val resUri = getByUriOrPrenormalize(url) match {
        case Success(Left(uri)) => {
          if (candidates.nonEmpty) {
            session.onTransactionSuccess {
              updateQueue.send(NormalizationUpdateTask(uri.id.get, false, candidates))
            }
          }
          statsd.timing("normalizedURIRepo.internByUri.in_db", timer, ONE_IN_THOUSAND)
          uri
        }
        case Success(Right(prenormalizedUrl)) => {
          val normalization = SchemeNormalizer.findSchemeNormalization(prenormalizedUrl)
          val toBeSaved = NormalizedURI.withHash(normalizedUrl = prenormalizedUrl, normalization = normalization).withContentRequest(contentWanted)
          val newUri = {
            val saved = normalizedURIRepo.save(toBeSaved)
            statsd.timing("normalizedURIRepo.internByUri.new", timer, ONE_IN_THOUSAND)
            saved
          }
          urlRepo.save(URLFactory(url = url, normalizedUriId = newUri.id.get))
          session.onTransactionSuccess {
            updateQueue.send(NormalizationUpdateTask(newUri.id.get, true, candidates))
          }
          statsd.timing("normalizedURIRepo.internByUri.new.url_save", timer, ONE_IN_THOUSAND)
          newUri
        }
        case Failure(ex) => throw new UriInternException(s"could not parse or find url in db: $url", ex)
      }
      log.debug(s"[internByUri($url)] resUri=$resUri")

      val uriWithContentRequest = {
        val uriWithContentRequest = resUri.withContentRequest(contentWanted)
        if (resUri != uriWithContentRequest) normalizedURIRepo.save(uriWithContentRequest) else resUri
      }
      uriWithContentRequest
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
    if (uriString.length >= URLFactory.MAX_URL_SIZE) {
      Failure(new Exception(s"URI too long, refusing to prenormalize: $uriString"))
    } else {
      val prenormalized = normalizationService.prenormalize(uriString)
      statsd.timing(key = "normalizedURIRepo.prenormalize", timer, ONE_IN_THOUSAND)
      prenormalized
    }
  }

  def getByUri(url: String)(implicit session: RSession): Option[NormalizedURI] = {
    getByUriOrPrenormalize(url).map(_.left.toOption).toOption.flatten
  }

}
