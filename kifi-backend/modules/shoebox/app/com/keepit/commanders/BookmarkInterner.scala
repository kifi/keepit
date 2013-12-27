package com.keepit.commanders

import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.scraper.ScrapeSchedulerPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.KestrelCombinator

import play.api.libs.json._

import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import com.google.inject.{Inject, Singleton}
import com.keepit.normalizer.NormalizationCandidate
import scala.util.Try
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.keepit.heimdal.HeimdalContext

@Singleton
class BookmarkInterner @Inject() (
  db: Database,
  uriRepo: NormalizedURIRepo,
  scraper: ScrapeSchedulerPlugin,
  bookmarkRepo: BookmarkRepo,
  urlRepo: URLRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  airbrake: AirbrakeNotifier,
  keptAnalytics: KeepingAnalytics,
  keepsAbuseControll: KeepsAbuseController,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends Logging {

  def internBookmarks(value: JsValue, user: User, experiments: Set[ExperimentType], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): Seq[Bookmark] = {
    val referenceId = UUID.randomUUID
    log.info(s"[internBookmarks] user=(${user.id} ${user.firstName} ${user.lastName}) source=$source installId=$installationId value=$value $referenceId ")
    val parseStart = System.currentTimeMillis()
    val bookmarks = getBookmarksFromJson(value).sortWith { case (a, b) =>
      val aUrl = (a \ "url").asOpt[String]
      val bUrl = (b \ "url").asOpt[String]
      (aUrl, bUrl) match {
        case (Some(au), Some(bu)) => au < bu
        case (Some(au), None) => true
        case (None, Some(bu)) => false
        case (None, None) => true
      }
    }
    log.info(s"[internBookmarks-$referenceId] Parsing took: ${System.currentTimeMillis - parseStart}ms")
    keepsAbuseControll.inspect(user.id.get, bookmarks.size)
    val count = new AtomicInteger(0)
    val total = bookmarks.size
    val batchConcurrency = 1
    val batchSize = 100

    val persistedBookmarksWithUris = bookmarks.grouped(batchSize).grouped(batchConcurrency).map { concurrentGroup =>
      concurrentGroup.par.map { bms =>
        val startTime = System.currentTimeMillis
        log.info(s"[internBookmarks-$referenceId] Persisting $batchSize bookmarks: ${count.get}/$total")
        val persisted = db.readWrite(attempts = 2) { implicit session =>
          bms.map { bm => internUriAndBookmark(bm, user, experiments, source, mutatePrivacy) }.flatten
        }
        log.info(s"[internBookmarks-$referenceId] Done with ${count.addAndGet(bms.size)}/$total. Took ${System.currentTimeMillis - startTime}ms")
        persisted
      }.flatten.toList
    }.flatten.toList

    log.info(s"[internBookmarks-$referenceId] Requesting scrapes")
    val newKeeps = persistedBookmarksWithUris collect { case (bm, uri, isNewBookmark) if isNewBookmark => bm }
    keptAnalytics.keptPages(user.id.get, newKeeps, context)

    val persistedBookmarks = persistedBookmarksWithUris.map(_._1)
    log.info(s"[internBookmarks-$referenceId] Done!")
    persistedBookmarks
  }

  private def getBookmarksFromJson(value: JsValue): Seq[JsObject] = {
    value match {
      case JsArray(elements) => elements.map(getBookmarksFromJson).flatten
      case json: JsObject if json.keys.contains("children") => getBookmarksFromJson(json \ "children")
      case json: JsObject => Seq(json)
      case _ =>
        airbrake.notify(s"error parsing bookmark import json $value")
        Seq()
    }
  }

  private def internBookmark(uri: NormalizedURI, user: User, isPrivate: Boolean, mutatePrivacy: Boolean, experiments: Set[ExperimentType],
      installationId: Option[ExternalId[KifiInstallation]], source: BookmarkSource, title: Option[String], url: String)(implicit session: RWSession) = {
    bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get, excludeState = None) match {
      case Some(bookmark) =>
        val keepWithPrivate = if (mutatePrivacy) bookmark.copy(isPrivate = isPrivate) else bookmark
        val keep = if (!bookmark.isActive) { keepWithPrivate.withUrl(url).withActive(true).copy(createdAt = clock.now) } else keepWithPrivate
        val keepWithTitle = keep.withTitle(title orElse bookmark.title orElse uri.title)
        val persistedKeep = if(keepWithTitle != bookmark) bookmarkRepo.save(keepWithTitle) else bookmark
        (false, persistedKeep)
      case None =>
        val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        (true, bookmarkRepo.save(BookmarkFactory(uri, user.id.get, title orElse uri.title, urlObj, source, isPrivate, installationId)))
    }
  }

  private def internUriAndBookmark(json: JsObject, user: User, experiments: Set[ExperimentType], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit session: RWSession): Option[(Bookmark, NormalizedURI, Boolean)] = try {
    val startTime = System.currentTimeMillis
    val title = (json \ "title").asOpt[String]
    val url = (json \ "url").as[String]
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)
    if (!url.toLowerCase.startsWith("javascript:")) {
      log.debug("interning bookmark %s with title [%s]".format(json, title))

      val uri = {
        val initialURI = uriRepo.internByUri(url, NormalizationCandidate(json):_*)
        if (initialURI.state == NormalizedURIStates.ACTIVE | initialURI.state == NormalizedURIStates.INACTIVE)
          uriRepo.save(initialURI.withState(NormalizedURIStates.SCRAPE_WANTED))
        else initialURI
      }
      val (isNewKeep, bookmark) = internBookmark(uri, user, isPrivate, mutatePrivacy, experiments, installationId, source, title, url)

      if (uri.state == NormalizedURIStates.SCRAPE_WANTED) {
        Try(scraper.scheduleScrape(uri))
      }

      session.conn.commit()

      Some((bookmark, uri, isNewKeep))
    } else {
      None
    }
  } catch {
    case e: Exception =>
      //note that at this point we continue on. we don't want to mess the upload of entire user bookmarks because of one bad bookmark.
      airbrake.notify(AirbrakeError(
        exception = e,
        message = Some(s"Exception while loading one of the bookmarks of user $user: ${e.getMessage} from json: $json source: $source")))
      None
  }
}
