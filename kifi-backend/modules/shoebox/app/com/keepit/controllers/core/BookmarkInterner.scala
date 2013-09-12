package com.keepit.controllers.core

import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck._

import play.api.libs.json._

import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import com.google.inject.{Inject, Singleton}
import com.keepit.normalizer.NormalizationCandidate

@Singleton
class BookmarkInterner @Inject() (
  db: Database,
  uriRepo: NormalizedURIRepo,
  scraper: ScraperPlugin,
  bookmarkRepo: BookmarkRepo,
  urlRepo: URLRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  healthcheckPlugin: HealthcheckPlugin,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends Logging {

  def internBookmarks(value: JsValue, user: User, experiments: Set[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, experiments, source, installationId)} flatten).toList
    case json: JsObject if(json.keys.contains("children")) => internBookmarks(json \ "children" , user, experiments, source)
    case json: JsObject => List(internBookmark(json, user, experiments, source)).flatten
    case e: Throwable => throw new Exception("can't figure what to do with %s".format(e))
  }

  def internBookmark(uri: NormalizedURI, user: User, isPrivate: Boolean, experiments: Set[State[ExperimentType]],
      installationId: Option[ExternalId[KifiInstallation]], source: BookmarkSource, title: Option[String], url: String) = {
    db.readWrite(attempts = 2) { implicit s =>
      bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get, excludeState = None) match {
        case Some(bookmark) if bookmark.isActive => Some(bookmark.copy(isPrivate = isPrivate))
        case Some(bookmark) => Some(bookmarkRepo.save(bookmark.copy(state = BookmarkStates.ACTIVE, isPrivate = isPrivate, title = title orElse(uri.title), url = url)))
        case None =>
          Events.userEvent(EventFamilies.SLIDER, "newKeep", user, experiments, installationId.map(_.id).getOrElse(""), JsObject(Seq("source" -> JsString(source.value))))
          val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
          Some(bookmarkRepo.save(BookmarkFactory(uri, user.id.get, title orElse uri.title, urlObj, source, isPrivate, installationId)))
      }
    }
  }

  private def internBookmark(json: JsObject, user: User, experiments: Set[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): Option[Bookmark] = try {
    val title = (json \ "title").asOpt[String]
    val url = (json \ "url").as[String]
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)
    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))

      val uri = db.readWrite(attempts = 2) { implicit s =>
        val initialURI = uriRepo.internByUri(url, NormalizationCandidate(json):_*)
        if (initialURI.state == NormalizedURIStates.ACTIVE | initialURI.state == NormalizedURIStates.INACTIVE)
          uriRepo.save(initialURI.withState(NormalizedURIStates.SCRAPE_WANTED))
        else initialURI
      }

      if (uri.state == NormalizedURIStates.SCRAPE_WANTED) scraper.asyncScrape(uri)
      internBookmark(uri, user, isPrivate, experiments, installationId, source, title, url)

    } else {
      None
    }
  } catch {
    case e: Exception =>
      //note that at this point we continue on. we don't want to mess the upload of entire user bookmarks because of one bad bookmark.
      healthcheckPlugin.addError(HealthcheckError(Some(e), None, None, Healthcheck.API,
        Some(s"Exception while loading one of the bookmarks of user $user: ${e.getMessage} from json: $json source: $source")))
      None
  }
}
