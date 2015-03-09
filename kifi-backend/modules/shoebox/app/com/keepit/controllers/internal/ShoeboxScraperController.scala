package com.keepit.controllers.internal

import com.google.inject.{ Singleton, Provider, Inject }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URI, URIParser }
import com.keepit.common.performance._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.Signature
import play.api.mvc.Action
import play.api.libs.json.{ JsObject, JsArray, JsBoolean, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class ShoeboxScraperController @Inject() (
  urlPatternRules: UrlPatternRulesCommander,
  db: Database,
  imageInfoRepo: ImageInfoRepo,
  normUriRepo: NormalizedURIRepo,
  airbrake: AirbrakeNotifier,
  pageInfoRepo: PageInfoRepo,
  normalizationServiceProvider: Provider[NormalizationService],
  normalizedURIInterner: NormalizedURIInterner,
  scrapeInfoRepo: ScrapeInfoRepo,
  keepRepo: KeepRepo,
  latestKeepUrlCache: LatestKeepUrlCache,
  scraperHelper: ScraperCallbackHelper)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getLatestKeep() = Action(parse.json) { request =>
    val url = request.body.as[String]
    val bookmarkOpt = db.readOnlyMaster(2) { implicit session =>
      latestKeepUrlCache.getOrElseOpt(LatestKeepUrlKey(url)) {
        normUriRepo.getByNormalizedUrl(url).flatMap { uri =>
          keepRepo.latestKeep(uri.id.get, url)
        }
      }
    }
    log.debug(s"[getLatestKeep($url)] $bookmarkOpt")
    Ok(Json.toJson(bookmarkOpt))
  }

  def saveNormalizedURI() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val normalizedUri = request.body.as[NormalizedURI]
    val saved = scraperHelper.saveNormalizedURI(normalizedUri)
    log.debug(s"[saveNormalizedURI] time-lapsed:${System.currentTimeMillis - ts} url=(${normalizedUri.url}) result=$saved")
    Ok(Json.toJson(saved))
  }

  def getAllURLPatternRules() = Action { request =>
    val patterns = urlPatternRules.rules().rules
    Ok(Json.toJson(patterns))
  }

  def updateNormalizedURI(uriId: Id[NormalizedURI]) = SafeAsyncAction(parse.tolerantJson) { request =>
    val saveResult = Try {
      // Handle serialization in session to be transactional.
      val originalNormalizedUri = db.readOnlyMaster { implicit s => normUriRepo.get(uriId) }
      val originalJson = Json.toJson(originalNormalizedUri).as[JsObject]
      val newNormalizedUriResult = Json.fromJson[NormalizedURI](originalJson ++ request.body.as[JsObject])

      newNormalizedUriResult.fold({ invalid =>
        val error = "Could not deserialize NormalizedURI ($uriId) update: $invalid\nOriginal: $originalNormalizedUri\nbody: ${request.body}"
        airbrake.notify(error)
        throw new Exception(error)
      }, { normalizedUri =>
        scraperHelper.saveNormalizedURI(normalizedUri)
      })
    }
    saveResult match {
      case Success(res) =>
        Ok
      case Failure(ex) =>
        log.error(s"Could not deserialize NormalizedURI ($uriId) update: $ex\nbody: ${request.body}")
        airbrake.notify(s"Could not deserialize NormalizedURI ($uriId) update", ex)
        throw ex
    }
  }

  def getProxy(url: String) = SafeAsyncAction { request =>
    val httpProxyOpt = db.readOnlyReplica(2) { implicit session =>
      urlPatternRules.getProxy(url)
    }
    log.debug(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def getProxyP = SafeAsyncAction(parse.tolerantJson) { request =>
    val url = request.body.as[String]
    val httpProxyOpt = db.readOnlyReplica(2) { implicit session =>
      urlPatternRules.getProxy(url)
    }
    Ok(Json.toJson(httpProxyOpt))
  }

  def assignScrapeTasks(zkId: Id[ScraperWorker], max: Int) = Action.async { request =>
    scraperHelper.assignTasks(zkId, max) map { res =>
      Ok(Json.toJson(res))
    }
  }

  def recordScrapedNormalization() = Action.async(parse.tolerantJson) { request =>
    val candidateUrlString = (request.body \ "url").as[String]
    val candidateUrl = URI.parse(candidateUrlString).get.toString()
    timing(s"recordScrapedNormalization.$candidateUrl") {
      val candidateNormalization = (request.body \ "normalization").as[Normalization]
      val scrapedCandidate = ScrapedCandidate(candidateUrl, candidateNormalization)

      val uriId = (request.body \ "id").as[Id[NormalizedURI]](Id.format)
      val signature = Signature((request.body \ "signature").as[String])
      val scrapedUri = db.readOnlyMaster { implicit session => normUriRepo.get(uriId) }
      normalizationServiceProvider.get.update(NormalizationReference(scrapedUri, signature = Some(signature)), scrapedCandidate).map { newReferenceOption =>
        (request.body \ "alternateUrls").asOpt[Set[String]].foreach { alternateUrls =>
          val bestReference = newReferenceOption.map { newReferenceId =>
            db.readOnlyMaster { implicit session =>
              normUriRepo.get(newReferenceId)
            }
          } getOrElse scrapedUri
          bestReference.normalization.map(AlternateCandidate(scrapedUri.url, _)).foreach { bestCandidate =>
            alternateUrls.foreach { alternateUrlString =>
              val alternateUrl = URI.parse(alternateUrlString).get.toString()
              val uri = db.readOnlyMaster { implicit session =>
                normalizedURIInterner.getByUri(alternateUrl)
              }
              uri match {
                case Some(existingUri) if existingUri.id.get == bestReference.id.get => // ignore
                case _ => try {
                  db.readWrite { implicit session =>
                    normalizedURIInterner.internByUri(alternateUrl, bestCandidate)
                  }
                } catch { case ex: Throwable => log.error(s"Failed to intern alternate url $alternateUrl for $bestCandidate") }
              }
            }
          }
        }
        Ok
      }
    }
  }

  // todo: revisit
  def recordPermanentRedirect() = Action.async(parse.tolerantJson) { request =>
    val ts = System.currentTimeMillis
    log.debug(s"[recordPermanentRedirect] body=${request.body}")
    val args = request.body.as[JsArray].value
    require(!args.isEmpty && args.length == 2, "Both uri and redirect need to be supplied")
    val uri = args(0).as[NormalizedURI]
    val redirect = args(1).as[HttpRedirect]
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
    require(redirect.isPermanent || redirect.isShortener, "HTTP redirect is neither permanent nor from a shortener.")
    val verifiedCandidateOption = normalizationServiceProvider.get.prenormalize(redirect.newDestination).toOption.flatMap { prenormalizedDestination =>
      db.readWrite { implicit session =>
        val (candidateUrl, candidateNormalizationOption) = normUriRepo.getByNormalizedUrl(prenormalizedDestination) match {
          case None => (prenormalizedDestination, SchemeNormalizer.findSchemeNormalization(prenormalizedDestination))
          case Some(referenceUri) if referenceUri.state != NormalizedURIStates.REDIRECTED => (referenceUri.url, referenceUri.normalization)
          case Some(reverseRedirectUri) if reverseRedirectUri.redirect == Some(uri.id.get) =>
            (reverseRedirectUri.url, SchemeNormalizer.findSchemeNormalization(reverseRedirectUri.url))
          case Some(redirectedUri) =>
            val referenceUri = normUriRepo.get(redirectedUri.redirect.get)
            (referenceUri.url, referenceUri.normalization)
        }
        candidateNormalizationOption.map(VerifiedCandidate(candidateUrl, _))
      }
    }

    val resFutureOption = verifiedCandidateOption.map { verifiedCandidate =>
      val toBeRedirected = NormalizationReference(uri, correctedNormalization = Some(Normalization.MOVED))
      val updateFuture = normalizationServiceProvider.get.update(toBeRedirected, verifiedCandidate)
      // Scraper reports entire NormalizedUri objects with a major chance of stale data / race conditions
      // The following is meant for synchronisation and should be revisited when scraper apis are rewritten to report modified fields only

      updateFuture.map {
        case Some(update) =>
          val redirectedUri = db.readOnlyMaster { implicit session => normUriRepo.get(uri.id.get) }
          log.debug(s"[recordedPermanentRedirect($uri, $redirect)] time-lapsed: ${System.currentTimeMillis - ts} result=$redirectedUri")
          redirectedUri
        case None =>
          log.warn(s"[failedToRecordPermanentRedirect($uri, $redirect)] Normalization update failed - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
          uri
      }
    }

    val resFuture = resFutureOption getOrElse {
      log.warn(s"[failedToRecordPermanentRedirect($uri, $redirect)] Redirection normalization empty - time-lapsed: ${System.currentTimeMillis - ts} result=$uri")
      Future.successful(uri)
    }

    resFuture.map { res => Ok(Json.toJson(res)) }
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    statsd.gauge("saveScrapeInfo", 1)
    val ts = System.currentTimeMillis
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = scraperHelper.assignLock.withLock {
      db.readWrite(attempts = 3) { implicit s =>
        val nuri = normUriRepo.get(info.uriId)
        if (URI.parse(nuri.url).isFailure) {
          scrapeInfoRepo.save(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
          airbrake.notify(s"can't parse $nuri, not passing it to the scraper, marking as unscrapable")
        } else {
          scrapeInfoRepo.save(info)
        }
      }
    }
    log.info(s"[saveScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} result=$saved")
    Ok
  }

}
