package com.keepit.commanders

import java.lang.reflect.UndeclaredThrowableException
import java.util.UUID

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.{ FutureHelpers, ExecutionContext }
import com.keepit.search.SearchServiceClient
import scala.concurrent.duration._
import com.keepit.common.core._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.common.util.Debouncing
import com.keepit.heimdal.{ HeimdalContext, HeimdalServiceClient }
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.normalizer.{ NormalizationService, NormalizationCandidate, NormalizedURIInterner }
import com.keepit.rover.RoverServiceClient
import com.keepit.slack.LibraryToSlackChannelPusher
import org.joda.time.{ Period, DateTime }
import play.api.libs.json.Json

import scala.util.{ Failure, Success, Try }

private case class InternedUriAndKeep(keep: Keep, uri: NormalizedURI, isNewKeep: Boolean, wasInactiveKeep: Boolean)

case class KeepInternResponse(newKeeps: Seq[Keep], existingKeeps: Seq[Keep], failures: Seq[RawBookmarkRepresentation]) {
  def successes = newKeeps ++ existingKeeps
}

@ImplementedBy(classOf[KeepInternerImpl])
trait KeepInterner {
  private[commanders] def deDuplicate(rawBookmarks: Seq[RawBookmarkRepresentation]): Seq[RawBookmarkRepresentation]
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse
  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val response = internRawBookmarksWithStatus(rawBookmarks, ownerId, library, source)
    (response.successes, response.failures)
  }
  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)] = {
    internRawBookmarksWithStatus(Seq(rawBookmark), ownerId, library, source) match {
      case KeepInternResponse(Seq(newKeep), _, _) => Success((newKeep, true))
      case KeepInternResponse(_, Seq(existingKeep), _) => Success((existingKeep, false))
      case KeepInternResponse(_, _, Seq(failedKeep)) => Failure(new Exception(s"could not intern $failedKeep"))
    }
  }
}

@Singleton
class KeepInternerImpl @Inject() (
  db: Database,
  normalizedURIInterner: NormalizedURIInterner,
  normalizedURIRepo: NormalizedURIRepo,
  keepRepo: KeepRepo,
  libraryRepo: LibraryRepo,
  keepCommander: KeepCommander,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  airbrake: AirbrakeNotifier,
  libraryAnalytics: LibraryAnalytics,
  keepsAbuseMonitor: KeepsAbuseMonitor,
  userValueRepo: UserValueRepo,
  rawKeepRepo: RawKeepRepo,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  heimdalClient: HeimdalServiceClient,
  roverClient: RoverServiceClient,
  libraryNewFollowersCommander: LibraryNewKeepsCommander,
  integrityHelpers: UriIntegrityHelpers,
  sourceAttrRepo: KeepSourceAttributionRepo,
  libToSlackProcessor: LibraryToSlackChannelPusher,
  normalizationService: NormalizationService,
  searchClient: SearchServiceClient,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends KeepInterner with Logging with Debouncing {

  implicit private val fj = ExecutionContext.fj

  private[commanders] def deDuplicate(rawBookmarks: Seq[RawBookmarkRepresentation]): Seq[RawBookmarkRepresentation] =
    rawBookmarks.map(b => (b.url, b)).toMap.values.toList

  // Persists keeps to RawKeep, which will be batch processed. Very minimal pre-processing.
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit = {
    log.info(s"[persistRawKeeps] persisting batch of ${rawKeeps.size} keeps")
    val newImportId = importId.getOrElse(UUID.randomUUID.toString)

    val deduped = rawKeeps.distinctBy(_.url) map (_.copy(importId = Some(newImportId)))

    if (deduped.nonEmpty) {
      val userId = deduped.head.userId
      val total = deduped.size

      keepsAbuseMonitor.inspect(userId, total)
      libraryAnalytics.keepImport(userId, clock.now, context, total, deduped.headOption.map(_.source).getOrElse(KeepSource.bookmarkImport))

      deduped.grouped(500).foreach { rawKeepGroup =>
        // insertAll fails if any of the inserts failed
        log.info(s"[persistRawKeeps] Persisting ${rawKeepGroup.length} raw keeps")
        val bulkAttempt = db.readWrite(attempts = 4) { implicit session =>
          rawKeepRepo.insertAll(rawKeepGroup)
        }
        if (bulkAttempt.isFailure) {
          log.info(s"[persistRawKeeps] Failed bulk. Trying one at a time")
          val singleAttempt = db.readWrite { implicit session =>
            rawKeepGroup.toList.map { rawKeep =>
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

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, ownerId, library, source)

    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKs, existingKs) => (newKs.map(_.keep), existingKs.map(_.keep))
    }

    reportNewKeeps(ownerId, newKeeps, library, context, notifyExternalSources = true)

    KeepInternResponse(newKeeps, existingKeeps, failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource) = {
    // For batches, if we can't prenormalize, silently drop. This is a low bar, and usually means it couldn't *ever* be a valid keep.
    val validUrls = bms.filter(b => httpPrefix.findPrefixOf(b.url.toLowerCase).isDefined && normalizationService.prenormalize(b.url).isSuccess)

    val (persisted, failed) = db.readWriteBatch(validUrls, attempts = 5) {
      case ((session, bm)) =>
        implicit val s = session
        internUriAndBookmark(bm, ownerId, library, source).get // Exception caught by readWriteBatch
    }.partition { case (bm, res) => res.isSuccess }

    if (failed.nonEmpty) {
      airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks (${validUrls.size} valid): look app.log for urls"), userId = Some(ownerId)))
      failed.foreach { f => log.warn(s"failed to persist raw bookmarks of user $ownerId from $source: ${f._1.url}", f._2.failed.get) }
    }
    val failedRaws: Seq[RawBookmarkRepresentation] = failed.keys.toList
    (persisted.values.map(_.get).toSeq, failedRaws)
  }

  private val httpPrefix = "https?://".r

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], library: Library, source: KeepSource)(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, contentWanted = true, candidates = NormalizationCandidate.fromRawBookmark(rawBookmark))
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $ownerId, from $source", t)
      }

      log.info(s"[keepinterner] Persisting keep ${rawBookmark.url}, ${rawBookmark.keptAt}, ${clock.now}")
      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, ownerId, library, source, rawBookmark.title, rawBookmark.url, rawBookmark.keptAt.getOrElse(clock.now), rawBookmark.sourceAttribution, rawBookmark.note)
      Success(InternedUriAndKeep(bookmark, uri, isNewKeep, wasInactiveKeep))
    } else {
      Failure(new Exception(s"bookmark url is not an http protocol: ${rawBookmark.url}"))
    }
  } catch {
    case e: Throwable =>
      Failure(e)
  }

  private def internKeep(uri: NormalizedURI, userId: Id[User], library: Library, source: KeepSource,
    title: Option[String], url: String, keptAt: DateTime,
    sourceAttribution: Option[SourceAttribution], note: Option[String])(implicit session: RWSession) = {

    val keepOpt = keepRepo.getPrimaryByUriAndLibrary(uri.id.get, library.id.get) // This in effect makes libraries only able to hold one keep per uriId

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
            try {
              keepCommander.persistKeep(keep, Set(userId), Set(library.id.get))
            } catch {
              case ex: UndeclaredThrowableException =>
                log.warn(s"[keepinterner] Persisting keep failed of ${keep.url} (${keep.id.get})", ex)
                throw ex.getUndeclaredThrowable
            }
          }
        (false, wasInactiveKeep, savedKeep)
      case None =>
        val savedAttr = sourceAttribution.map { attr => sourceAttrRepo.save(KeepSourceAttribution(attribution = attr)) }
        val keep = Keep(
          title = trimmedTitle orElse uri.title,
          userId = userId,
          uriId = uri.id.get,
          url = url,
          source = source,
          visibility = library.visibility,
          libraryId = Some(library.id.get),
          keptAt = keptAt,
          sourceAttributionId = savedAttr.flatMap { _.id },
          note = note,
          originalKeeperId = Some(userId)
        )
        val improvedKeep = try {
          keepCommander.persistKeep(integrityHelpers.improveKeepSafely(uri, keep), Set(userId), Set(library.id.get))
        } catch {
          case ex: UndeclaredThrowableException =>
            log.warn(s"[keepinterner] Persisting keep failed of ${keep.url}", ex)
            throw ex.getUndeclaredThrowable
        }

        (true, false, improvedKeep)
    }
    if (wasInactiveKeep) {
      // A inactive keep may have had tags already. Index them if any.
      keepToCollectionRepo.getCollectionsForKeep(internedKeep.id.get) foreach { cid => collectionRepo.collectionChanged(cid, inactivateIfEmpty = false) }
    }

    (isNewKeep, wasInactiveKeep, internedKeep)
  }

  private def reportNewKeeps(keeperUserId: Id[User], keeps: Seq[Keep], library: Library, ctx: HeimdalContext, notifyExternalSources: Boolean) = {
    if (keeps.nonEmpty) {
      // Analytics
      libraryAnalytics.keptPages(keeperUserId, keeps, library, ctx)
      heimdalClient.processKeepAttribution(keeperUserId, keeps)
      searchClient.updateKeepIndex()

      // Make external notifications & fetch
      if (notifyExternalSources && KeepSource.discrete.contains(keeps.head.source)) { // Only report first to not spam
        SafeFuture { libraryNewFollowersCommander.notifyFollowersOfNewKeeps(library, keeps.head) }
        db.readWrite { implicit s =>
          libToSlackProcessor.scheduleLibraryToBePushed(library.id.get, clock.now plus (if (keeps.forall(_.title.isDefined)) Period.seconds(20) else Period.seconds(40)))
        }
        FutureHelpers.sequentialExec(keeps) { keep =>
          val nuri = db.readOnlyMaster { implicit session =>
            normalizedURIRepo.get(keep.uriId)
          }
          roverClient.fetchAsap(nuri.id.get, nuri.url)
        }
      }

      // Update data-dependencies
      db.readWrite(attempts = 3) { implicit s =>
        libraryRepo.updateLastKept(library.id.get)
        Try(libraryRepo.save(libraryRepo.getNoCache(library.id.get).copy(keepCount = keepRepo.getCountByLibrary(library.id.get)))) // wrapped in a Try because this is super deadlock prone
      }
    }
  }

}
