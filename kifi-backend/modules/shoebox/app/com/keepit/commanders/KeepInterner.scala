package com.keepit.commanders

import java.util.UUID

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.core._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext, HeimdalServiceClient }
import com.keepit.model._
import com.keepit.normalizer.{ NormalizationCandidate, NormalizedURIInterner }
import com.keepit.scraper.ScrapeScheduler
import play.api.libs.json.Json

import scala.util.{ Failure, Random, Success, Try }

case class InternedUriAndKeep(bookmark: Keep, uri: NormalizedURI, isNewKeep: Boolean, wasInactiveKeep: Boolean)

@Singleton
class KeepInterner @Inject() (
  db: Database,
  normalizedURIInterner: NormalizedURIInterner,
  scraper: ScrapeScheduler,
  keepRepo: KeepRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  urlRepo: URLRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  airbrake: AirbrakeNotifier,
  keptAnalytics: KeepingAnalytics,
  keepsAbuseMonitor: KeepsAbuseMonitor,
  rawBookmarkFactory: RawBookmarkFactory,
  userValueRepo: UserValueRepo,
  rawKeepRepo: RawKeepRepo,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  elizaClient: ElizaServiceClient,
  heimdalClient: HeimdalServiceClient,
  libraryCommander: LibraryCommander,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends Logging {

  implicit private val fj = ExecutionContext.fj

  private[commanders] def deDuplicateRawKeep(rawKeeps: Seq[RawKeep]): Seq[RawKeep] =
    rawKeeps.map(b => (b.url, b)).toMap.values.toList

  private[commanders] def deDuplicate(rawBookmarks: Seq[RawBookmarkRepresentation]): Seq[RawBookmarkRepresentation] =
    rawBookmarks.map(b => (b.url, b)).toMap.values.toList

  // Persists keeps to RawKeep, which will be batch processed. Very minimal pre-processing.
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit = {
    log.info(s"[persistRawKeeps] persisting batch of ${rawKeeps.size} keeps")
    val newImportId = importId.getOrElse(UUID.randomUUID.toString)

    val deduped = deDuplicateRawKeep(rawKeeps) map (_.copy(importId = Some(newImportId)))

    if (deduped.nonEmpty) {
      val userId = deduped.head.userId
      val total = deduped.size

      keepsAbuseMonitor.inspect(userId, total)
      keptAnalytics.keepImport(userId, clock.now, context, total, deduped.headOption.map(_.source).getOrElse(KeepSource.bookmarkImport))

      db.readWrite(attempts = 3) { implicit session =>
        // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
        // If it happens, the user will experience the % complete jumping around a bit until it's finished.
        userValueRepo.setValue(userId, UserValueName.BOOKMARK_IMPORT_LAST_START, clock.now)
        userValueRepo.setValue(userId, UserValueName.BOOKMARK_IMPORT_DONE, 0)
        userValueRepo.setValue(userId, UserValueName.BOOKMARK_IMPORT_TOTAL, total)
        userValueRepo.setValue(userId, UserValueName.bookmarkImportContextName(newImportId), Json.toJson(context))
      }

      deduped.grouped(500).toList.map { rawKeepGroup =>
        // insertAll fails if any of the inserts failed
        log.info(s"[persistRawKeeps] Persisting ${rawKeepGroup.length} raw keeps")
        val bulkAttempt = db.readWrite(attempts = 2) { implicit session =>
          rawKeepRepo.insertAll(rawKeepGroup)
        }
        log.info(s"[persistRawKeeps] Persist result: $bulkAttempt")
        if (bulkAttempt.isFailure) {
          log.info(s"[persistRawKeeps] Trying one at a time")
          val singleAttempt = db.readWrite { implicit session =>
            rawKeepGroup.map { rawKeep =>
              rawKeep -> rawKeepRepo.insertOne(rawKeep)
            }
          }
          val (_, failuresWithRaws) = singleAttempt.partition(_._2.isSuccess)
          val failedUrls = failuresWithRaws.map(_._1.url)
          if (failedUrls.nonEmpty) {
            airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failedUrls.size} raw keeps: ${failedUrls mkString ","}"), userId = Some(userId)))
          }
        }
      }
      rawKeepImporterPlugin.processKeeps(broadcastToOthers = true)
    }
  }

  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val (newKeeps, existingKeeps, failures) = internRawBookmarksWithStatus(rawBookmarks, userId, library, source, installationId)
    (newKeeps ++ existingKeeps, failures)
  }

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): (Seq[Keep], Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, userId, library, source, installationId)
    val createdKeeps = persistedBookmarksWithUris collect {
      case InternedUriAndKeep(bm, uri, isNewBookmark, wasInactiveKeep) if isNewBookmark => bm
    }
    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKeeps, existingKeeps) => (newKeeps map (_.bookmark), existingKeeps map (_.bookmark))
    }

    keptAnalytics.keptPages(userId, createdKeeps, context)
    heimdalClient.processKeepAttribution(userId, createdKeeps)
    (newKeeps, existingKeeps, failures)
  }

  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], library: Library, source: KeepSource, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): Try[Keep] = {
    db.readWrite { implicit s =>
      internUriAndBookmark(rawBookmark, userId, library, source, installationId)
    } map { persistedBookmarksWithUri =>
      val bookmark = persistedBookmarksWithUri.bookmark
      if (persistedBookmarksWithUri.isNewKeep) {
        keptAnalytics.keptPages(userId, Seq(bookmark), context)
        heimdalClient.processKeepAttribution(userId, Seq(bookmark))
      }
      bookmark
    }
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource, installationId: Option[ExternalId[KifiInstallation]]) = {
    val (persisted, failed) = db.readWriteBatch(bms, attempts = 2) { (session, bm) =>
      internUriAndBookmark(bm, userId, library, source, installationId)(session)
    } map {
      case (bm, res) => bm -> res.flatten
    } partition {
      case (bm, res) => res.isSuccess
    }

    if (failed.nonEmpty) {
      airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks: look app.log for urls"), userId = Some(userId)))
      failed.foreach { f => log.error(s"failed to persist raw bookmarks of user $userId from $source: ${f._1.url}", f._2.failed.get) }
    }
    val failedRaws: Seq[RawBookmarkRepresentation] = failed.keys.toList
    (persisted.values.map(_.get).toSeq, failedRaws)
  }

  val MAX_RANDOM_SCHEDULE_DELAY: Int = 600000
  private val httpPrefix = "https?://".r

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], library: Library, source: KeepSource, installationId: Option[ExternalId[KifiInstallation]])(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      import com.keepit.model.NormalizedURIStates._
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, NormalizationCandidate(rawBookmark): _*)
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $userId, from $source", t)
      }
      if (uri.state == ACTIVE || uri.state == INACTIVE) {
        val date = source match {
          case KeepSource.bookmarkImport => currentDateTime.plus(Random.nextInt(MAX_RANDOM_SCHEDULE_DELAY))
          case KeepSource.keeper => START_OF_TIME
          case _ => currentDateTime
        }
        scraper.scheduleScrape(uri, date)
      }

      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, userId, library, installationId, source, rawBookmark.title, rawBookmark.url)
      Success(InternedUriAndKeep(bookmark, uri, isNewKeep, wasInactiveKeep))
    } else {
      Failure(new Exception(s"bookmark url is not an http protocol: ${rawBookmark.url}"))
    }
  } catch {
    case e: Throwable =>
      //note that at this point we continue on. we don't want to mess the upload of entire user bookmarks because of one bad bookmark.
      airbrake.notify(AirbrakeError(
        exception = e,
        message = Some(s"Exception while loading one of the bookmarks of user $userId: ${e.getMessage} from: $rawBookmark source: $source"),
        userId = Some(userId)))
      Failure(e)
  }

  private def internKeep(uri: NormalizedURI, userId: Id[User], library: Library,
    installationId: Option[ExternalId[KifiInstallation]], source: KeepSource, title: Option[String], url: String)(implicit session: RWSession) = {

    // todo: swap getPrimaryByUriAndUser to use libraries
    val (isNewKeep, wasInactiveKeep, internedKeep) = keepRepo.getPrimaryByUriAndUser(uri.id.get, userId) match {
      case Some(bookmark) =>
        val wasInactiveKeep = !bookmark.isActive
        val savedKeep = bookmark.copy(
          title = title orElse bookmark.title orElse uri.title,
          state = KeepStates.ACTIVE,
          visibility = library.visibility,
          libraryId = Some(library.id.get)
        ) |> { keep =>
            if (!bookmark.isActive) {
              keep.copy(url = url, createdAt = clock.now)
            } else keep
          } |> { keep =>
            keepRepo.save(keep)
          }
        (false, wasInactiveKeep, savedKeep)
      case None =>
        val urlObj = urlRepo.get(url, uri.id.get).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        val keep = Keep(title = title, userId = userId, uriId = uri.id.get, urlId = urlObj.id.get, url = url, source = source, visibility = library.visibility, libraryId = Some(library.id.get))
        (true, false, keepRepo.save(keep))
    }
    if (wasInactiveKeep) {
      // A inactive keep may have had tags already. Index them if any.
      keepToCollectionRepo.getCollectionsForKeep(internedKeep.id.get) foreach { cid => collectionRepo.collectionChanged(cid) }
    }

    (isNewKeep, wasInactiveKeep, internedKeep)
  }

}
