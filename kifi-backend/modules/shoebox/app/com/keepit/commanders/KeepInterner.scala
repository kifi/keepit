package com.keepit.commanders

import java.util.UUID

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
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
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.normalizer.{ NormalizationCandidate, NormalizedURIInterner }
import com.keepit.rover.RoverServiceClient
import org.joda.time.DateTime
import play.api.libs.json.Json

import scala.util.{ Failure, Success, Try }

case class InternedUriAndKeep(bookmark: Keep, uri: NormalizedURI, isNewKeep: Boolean, wasInactiveKeep: Boolean)

@ImplementedBy(classOf[KeepInternerImpl])
trait KeepInterner {
  private[commanders] def deDuplicateRawKeep(rawKeeps: Seq[RawKeep]): Seq[RawKeep]
  private[commanders] def deDuplicate(rawBookmarks: Seq[RawBookmarkRepresentation]): Seq[RawBookmarkRepresentation]
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit
  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation])
  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[Keep], Seq[RawBookmarkRepresentation])
  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)]
}

@Singleton
class KeepInternerImpl @Inject() (
  db: Database,
  normalizedURIInterner: NormalizedURIInterner,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  keepCommander: KeepCommander,
  countByLibraryCache: CountByLibraryCache,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  urlRepo: URLRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  airbrake: AirbrakeNotifier,
  libraryAnalytics: LibraryAnalytics,
  keepsAbuseMonitor: KeepsAbuseMonitor,
  rawBookmarkFactory: RawBookmarkFactory,
  userValueRepo: UserValueRepo,
  rawKeepRepo: RawKeepRepo,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  elizaClient: ElizaServiceClient,
  heimdalClient: HeimdalServiceClient,
  roverClient: RoverServiceClient,
  libraryCommander: LibraryCommander,
  subscriptionCommander: LibrarySubscriptionCommander,
  integrityHelpers: UriIntegrityHelpers,
  sourceAttrRepo: KeepSourceAttributionRepo,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends KeepInterner with Logging {

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
      libraryAnalytics.keepImport(userId, clock.now, context, total, deduped.headOption.map(_.source).getOrElse(KeepSource.bookmarkImport))

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

  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val (newKeeps, existingKeeps, failures) = internRawBookmarksWithStatus(rawBookmarks, userId, library, source)
    println("[RPB] newKeeps = " + newKeeps)
    println("[RPB] existingKeeps = " + existingKeeps)
    (newKeeps ++ existingKeeps, failures)
  }

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, userId, library, source)
    println("[RPB] persistedBookmarksWithUris = " + persistedBookmarksWithUris)
    if (persistedBookmarksWithUris.nonEmpty) {
      db.readWrite { implicit s =>
        libraryRepo.updateLastKept(library.id.get)
        Try(libraryRepo.save(libraryRepo.getNoCache(library.id.get).copy(keepCount = keepRepo.getCountByLibrary(library.id.get)))) // wrapped in a Try because this is super deadlock prone.
      }
    }
    val createdKeeps = persistedBookmarksWithUris collect {
      case InternedUriAndKeep(bm, uri, isNewBookmark, wasInactiveKeep) if isNewBookmark => bm
    }
    println("[RPB] createdKeeps = " + createdKeeps)
    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKeeps, existingKeeps) => (newKeeps map (_.bookmark), existingKeeps map (_.bookmark))
    }
    libraryAnalytics.keptPages(userId, createdKeeps, library, context)
    heimdalClient.processKeepAttribution(userId, createdKeeps)
    (newKeeps, existingKeeps, failures)
  }

  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)] = {
    db.readWrite(attempts = 2) { implicit s =>
      internUriAndBookmark(rawBookmark, userId, library, source)
    } map { persistedBookmarksWithUri =>
      val bookmark = persistedBookmarksWithUri.bookmark
      if (persistedBookmarksWithUri.isNewKeep) {
        if (library.kind == LibraryKind.USER_CREATED) SafeFuture { libraryCommander.notifyFollowersOfNewKeeps(library, bookmark) }
        libraryAnalytics.keptPages(userId, Seq(bookmark), library, context)
        heimdalClient.processKeepAttribution(userId, Seq(bookmark))
      }
      db.readWrite { implicit s =>
        libraryRepo.updateLastKept(library.id.get)
        Try(libraryRepo.save(libraryRepo.getNoCache(library.id.get).copy(keepCount = keepRepo.getCountByLibrary(library.id.get)))) // wrapped in a Try because this is super deadlock prone. Needs to be removed.
      }
      (bookmark, persistedBookmarksWithUri.isNewKeep)
    }
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], userId: Id[User], library: Library, source: KeepSource) = {
    val (persisted, failed) = bms.map { bm =>
      bm -> Try {
        db.readWrite(attempts = 3) { implicit session =>
          internUriAndBookmark(bm, userId, library, source).get
          // This is bad, and I'm not sure why we need to do it now. Previously, we could batch keeps into one db session.
          // It appears that when there's a problem, now, we're fetching from rover with ids that may not exist anymore.
          // So, forcing the Try evaluation to make the db session fail, and then recatching it.
          // Expect something more elegant soon. Signed, Andrew (May 22 2015)
        }
      }
    }.toMap partition {
      case (bm, res) => res.isSuccess
    }

    if (failed.nonEmpty) {
      airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks: look app.log for urls"), userId = Some(userId)))
      failed.foreach { f => log.error(s"failed to persist raw bookmarks of user $userId from $source: ${f._1.url}", f._2.failed.get) }
    }
    val failedRaws: Seq[RawBookmarkRepresentation] = failed.keys.toList
    (persisted.values.map(_.get).toSeq, failedRaws)
  }

  private val httpPrefix = "https?://".r

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], library: Library, source: KeepSource)(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, contentWanted = true, candidates = NormalizationCandidate.fromRawBookmark(rawBookmark))
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $userId, from $source", t)
      }

      if (KeepSource.discrete.contains(source)) {
        session.onTransactionSuccess {
          roverClient.fetchAsap(uri.id.get, uri.url)
        }
      }

      log.info(s"[keepinterner] Persisting keep ${rawBookmark.url}, ${rawBookmark.keptAt}, ${clock.now}")
      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, userId, library, source, rawBookmark.title, rawBookmark.url, rawBookmark.keptAt.getOrElse(clock.now), rawBookmark.sourceAttribution, rawBookmark.note)
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

  private def internKeep(uri: NormalizedURI, userId: Id[User], library: Library, source: KeepSource,
    title: Option[String], url: String, keptAt: DateTime,
    sourceAttribution: Option[SourceAttribution], note: Option[String])(implicit session: RWSession) = {

    val keepOpt = keepRepo.getPrimaryByUriAndLibrary(uri.id.get, library.id.get)

    val trimmedTitle = title.map(_.trim).filter(_.nonEmpty)

    val (isNewKeep, wasInactiveKeep, internedKeep) = keepOpt match {
      case Some(keep) =>
        val wasInactiveKeep = !keep.isActive
        val kNote = note orElse { if (wasInactiveKeep) None else keep.note }
        val kTitle = trimmedTitle orElse { if (wasInactiveKeep) None else keep.title } orElse uri.title

        val savedKeep = keep.copy(
          userId = userId,
          title = kTitle,
          state = KeepStates.ACTIVE,
          visibility = library.visibility,
          libraryId = Some(library.id.get),
          keptAt = keptAt,
          note = kNote,
          url = url
        ) |> { keep =>
            if (wasInactiveKeep) {
              keep.copy(createdAt = clock.now)
            } else keep
          } |> { keep =>
            keepCommander.persistKeep(keep, Set(userId), Set(library.id.get))
          }
        (false, wasInactiveKeep, savedKeep)
      case None =>
        val urlObj = urlRepo.get(url, uri.id.get).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        val savedAttr = sourceAttribution.map { attr => sourceAttrRepo.save(KeepSourceAttribution(attribution = attr)) }
        val keep = Keep(
          title = trimmedTitle orElse uri.title,
          userId = userId,
          uriId = uri.id.get,
          urlId = urlObj.id.get,
          url = url,
          source = source,
          visibility = library.visibility,
          libraryId = Some(library.id.get),
          keptAt = keptAt,
          sourceAttributionId = savedAttr.flatMap { _.id },
          note = note,
          originalKeeperId = Some(userId),
          entitiesHash = Some(KeepConnectionEntities(Set(library.id.get), Set(userId)).hash)
        )
        val improvedKeep = keepCommander.persistKeep(integrityHelpers.improveKeepSafely(uri, keep), Set(userId), Set(library.id.get))
        (true, false, improvedKeep)
    }
    if (wasInactiveKeep) {
      // A inactive keep may have had tags already. Index them if any.
      keepToCollectionRepo.getCollectionsForKeep(internedKeep.id.get) foreach { cid => collectionRepo.collectionChanged(cid, inactivateIfEmpty = false) }
    }

    println("[RPB] internedKeep = " + internedKeep)
    (isNewKeep, wasInactiveKeep, internedKeep)
  }

}
