package com.keepit.controllers.core

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.async._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.net._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.serializer.BookmarkSerializer
import com.keepit.common.logging.Logging

import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json._
import scala.concurrent.duration._

import com.keepit.common.analytics.ActivityStream

import com.google.inject.{Inject, Singleton}

@Singleton
class BookmarkInterner @Inject() (db: Database, uriRepo: NormalizedURIRepo, scraper: ScraperPlugin, bookmarkRepo: BookmarkRepo,
  urlRepo: URLRepo, socialUserInfoRepo: SocialUserInfoRepo, activityStream: ActivityStream)
    extends Logging {

  def internBookmarks(value: JsValue, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): List[Bookmark] = value match {
    case JsArray(elements) => (elements map {e => internBookmarks(e, user, experiments, source, installationId)} flatten).toList
    case json: JsObject if(json.keys.contains("children")) => internBookmarks(json \ "children" , user, experiments, source)
    case json: JsObject => List(internBookmark(json, user, experiments, source)).flatten
    case e: Throwable => throw new Exception("can't figure what to do with %s".format(e))
  }

  private def internBookmark(json: JsObject, user: User, experiments: Seq[State[ExperimentType]], source: BookmarkSource, installationId: Option[ExternalId[KifiInstallation]] = None): Option[Bookmark] = {
    val title = (json \ "title").as[String]
    val url = (json \ "url").as[String]
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)

    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))
      val (uri, needsToScrape) = db.readWrite(attempts = 2) { implicit s =>
        uriRepo.getByNormalizedUrl(url) match {
          case Some(uri) if uri.state == NormalizedURIStates.ACTIVE | uri.state == NormalizedURIStates.INACTIVE =>
            (uriRepo.save(uri.withState(NormalizedURIStates.SCRAPE_WANTED)), true)
          case Some(uri) => (uri, false)
          case None => (createNewURI(title, url), true)
        }
      }
      if (needsToScrape) scraper.asyncScrape(uri)

      val bookmark = db.readWrite(attempts = 2) { implicit s =>
        bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get) match {
          case Some(bookmark) if bookmark.isActive => Some(bookmark) // TODO: verify isPrivate?
          case Some(bookmark) => Some(bookmarkRepo.save(bookmark.withActive(true).withPrivate(isPrivate)))
          case None =>
            Events.userEvent(EventFamilies.SLIDER, "newKeep", user, experiments, installationId.map(_.id).getOrElse(""), JsObject(Seq("source" -> JsString(source.value))))
            val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
            Some(bookmarkRepo.save(BookmarkFactory(uri, user.id.get, title, urlObj, source, isPrivate, installationId)))
        }
      }
      if(bookmark.isDefined) addToActivityStream(user, bookmark.get)

      bookmark
    } else {
      None
    }
  }

  private def createNewURI(title: String, url: String)(implicit session: RWSession) =
    uriRepo.save(NormalizedURIFactory(title = title, url = url, state = NormalizedURIStates.SCRAPE_WANTED))

  private def addToActivityStream(user: User, bookmark: Bookmark) = {
    val social = db.readOnly { implicit session =>
      socialUserInfoRepo.getByUser(user.id.get).headOption.map(_.socialId.id).getOrElse("")
    }

    val json = Json.obj(
      "user" -> Json.obj(
        "id" -> user.id.get.id,
        "name" -> s"${user.firstName} ${user.lastName}",
        "avatar" -> s"https://graph.facebook.com/${social}/picture?height=150&width=150"),
      "bookmark" -> Json.obj(
        "id" -> bookmark.id.get.id,
        "isPrivate" -> bookmark.isPrivate,
        "title" -> bookmark.title,
        "uri" -> bookmark.url,
        "source" -> bookmark.source.value)
    )

    activityStream.streamActivity("bookmark", json)
  }
}
