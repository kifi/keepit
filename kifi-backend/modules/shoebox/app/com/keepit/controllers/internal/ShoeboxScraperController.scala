package com.keepit.controllers.internal

import com.google.inject.{ Provider, Inject }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URI, URIParser }
import com.keepit.common.performance._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.scraper.{ HttpRedirect, Signature }
import play.api.mvc.Action
import play.api.libs.json.{ JsObject, JsArray, JsBoolean, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class ShoeboxScraperController @Inject() (
  urlPatternRuleRepo: UrlPatternRuleRepo,
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
    val patterns = urlPatternRuleRepo.rules.rules
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
      urlPatternRuleRepo.getProxy(url)
    }
    log.debug(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def getProxyP = SafeAsyncAction(parse.tolerantJson) { request =>
    val url = request.body.as[String]
    val httpProxyOpt = db.readOnlyReplica(2) { implicit session =>
      urlPatternRuleRepo.getProxy(url)
    }
    Ok(Json.toJson(httpProxyOpt))
  }

  def assignScrapeTasks(zkId: Id[ScraperWorker], max: Int) = SafeAsyncAction { request =>
    val res = scraperHelper.assignTasks(zkId, max)
    Ok(Json.toJson(res))
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]) = Action { request =>
    val ts = System.currentTimeMillis
    val bookmarks = db.readOnlyReplica(2) { implicit session =>
      keepRepo.getByUriWithoutTitle(uriId)
    }
    log.debug(s"[getBookmarksByUriWithoutTitle($uriId)] time-lapsed:${System.currentTimeMillis - ts} bookmarks(len=${bookmarks.length}):${bookmarks.mkString}")
    Ok(Json.toJson(bookmarks))
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
          // todo(Léo): What follows is dangerous. Someone could mess up with our data by reporting wrong alternate Urls on its website. We need to do a specific content check.
          bestReference.normalization.map(ScrapedCandidate(scrapedUri.url, _)).foreach { bestCandidate =>
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
    require(redirect.isPermanent, "HTTP redirect is not permanent.")
    require(redirect.isLocatedAt(uri.url), "Current Location of HTTP redirect does not match normalized Uri.")
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

  def isUnscrapable(url: String, destinationUrl: Option[String]) = SafeAsyncAction { request =>
    val res = urlPatternRuleRepo.rules().isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.rules().isUnscrapable(destinationUrl.get))
    log.debug(s"[isUnscrapable($url, $destinationUrl)] result=$res")
    Ok(JsBoolean(res))
  }

  def isUnscrapableP() = SafeAsyncAction(parse.tolerantJson(maxLength = MaxContentLength)) { request =>
    val ts = System.currentTimeMillis
    val args = request.body.as[JsArray].value
    require(args != null && args.length >= 1, "Expect args to be url && opt[dstUrl] ")
    val url = args(0).as[String]
    val destinationUrl = if (args.length > 1) args(1).asOpt[String] else None
    val res = urlPatternRuleRepo.rules().isUnscrapable(url) || (destinationUrl.isDefined && urlPatternRuleRepo.rules().isUnscrapable(destinationUrl.get))
    log.debug(s"[isUnscrapableP] time-lapsed:${System.currentTimeMillis - ts} url=$url dstUrl=${destinationUrl.getOrElse("")} result=$res")
    Ok(JsBoolean(res))
  }

  // Todo(Eishay): Stop returning ImageInfo
  def saveImageInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val info = json.as[ImageInfo]
    scraperHelper.saveImageInfo(info)
    Ok
  }

  def savePageInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val info = json.as[PageInfo]
    val toSave = db.readOnlyMaster { implicit ro => pageInfoRepo.getByUri(info.uriId) } map { p => info.withId(p.id.get) } getOrElse info
    val saved = scraperHelper.savePageInfo(toSave)
    log.debug(s"[savePageInfo] result=$saved")
    Ok
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    val ts = System.currentTimeMillis
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = db.readWrite(attempts = 3) { implicit s =>
      scrapeInfoRepo.save(info)
    }
    log.debug(s"[saveScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} result=$saved")
    Ok
  }

}
