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

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], libraryOpt: Option[Library], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse

  // Because we're nice, you get some extras:
  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val response = internRawBookmarksWithStatus(rawBookmarks, ownerId, Some(library), source)
    (response.successes, response.failures)
  }
  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)] = {
    internRawBookmarksWithStatus(Seq(rawBookmark), ownerId, Some(library), source) match {
      case KeepInternResponse(Seq(newKeep), _, _) => Success((newKeep, true))
      case KeepInternResponse(_, Seq(existingKeep), _) => Success((existingKeep, false))
      case KeepInternResponse(_, _, Seq(failedKeep)) => Failure(new Exception(s"could not intern $failedKeep"))
      case kir: KeepInternResponse => Failure(new Exception(s"no valid URL processed $rawBookmark"))
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
        if (bulkAttempt.isFailure) { // fyi, this doesn't really happen in prod often
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

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], libraryOpt: Option[Library], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, ownerId, libraryOpt, source)

    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKs, existingKs) => (newKs.map(_.keep), existingKs.map(_.keep))
    }

    reportNewKeeps(ownerId, newKeeps, libraryOpt, context, notifyExternalSources = true)

    KeepInternResponse(newKeeps, existingKeeps, failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], ownerId: Id[User], libraryOpt: Option[Library], source: KeepSource) = {
    // For batches, if we can't prenormalize, silently drop. This is a low bar, and usually means it couldn't *ever* be a valid keep.
    val (validUrls, invalidUrls) = bms.partition(b => httpPrefix.findPrefixOf(b.url.toLowerCase).isDefined && normalizationService.prenormalize(b.url).isSuccess)

    val (persisted, failed) = db.readWriteBatch(validUrls, attempts = 5) {
      case ((session, bm)) =>
        implicit val s = session
        internUriAndBookmark(bm, ownerId, libraryOpt, source).get // Exception caught by readWriteBatch
    }.partition { case (bm, res) => res.isSuccess }

    val keeps = persisted.values.map(_.get).toSeq

    updateKeepTagsUsingNote(keeps.map(_.keep).filter(_.note.exists(_.nonEmpty)))

    if (failed.nonEmpty) {
      airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks (${validUrls.size} valid): look app.log for urls"), userId = Some(ownerId)))
      failed.foreach { f => log.warn(s"failed to persist raw bookmarks of user $ownerId from $source: ${f._1.url}", f._2.failed.get) }
    }
    val failedRaws: Seq[RawBookmarkRepresentation] = failed.keys.toList ++ invalidUrls
    (keeps, failedRaws)
  }

  private val httpPrefix = "https?://".r

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], libraryOpt: Option[Library], source: KeepSource)(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, contentWanted = true, candidates = NormalizationCandidate.fromRawBookmark(rawBookmark))
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $ownerId, from $source", t)
      }

      log.info(s"[keepinterner] Persisting keep ${rawBookmark.url}, ${rawBookmark.keptAt}, ${clock.now}")
      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, ownerId, libraryOpt, source, rawBookmark.title, rawBookmark.url, rawBookmark.keptAt.getOrElse(clock.now), rawBookmark.sourceAttribution, rawBookmark.noteFormattedLikeOurNotes)
      Success(InternedUriAndKeep(bookmark, uri, isNewKeep, wasInactiveKeep))
    } else {
      Failure(new Exception(s"bookmark url is not an http protocol: ${rawBookmark.url}"))
    }
  } catch {
    case e: Throwable =>
      Failure(e)
  }

  private def internKeep(uri: NormalizedURI, userId: Id[User], libraryOpt: Option[Library], source: KeepSource,
    title: Option[String], url: String, keptAt: DateTime,
    sourceAttribution: Option[SourceAttribution], note: Option[String])(implicit session: RWSession) = {

    val keepOpt = libraryOpt.flatMap { lib => keepRepo.getByUriAndLibrary(uri.id.get, lib.id.get, excludeState = None) }
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
          visibility = libraryOpt.map(_.visibility).getOrElse(LibraryVisibility.SECRET),
          libraryId = libraryOpt.map(_.id.get),
          keptAt = keptAt,
          note = kNote,
          url = url,
          organizationId = libraryOpt.flatMap(_.organizationId),
          connections = KeepConnections(libraryOpt.map(_.id.get).toSet[Id[Library]], Set(userId))
        ) |> { keep =>
            if (wasInactiveKeep) {
              keep.copy(createdAt = clock.now)
            } else keep
          } |> { keep =>
            try {
              keepCommander.persistKeep(keep)
            } catch {
              case ex: UndeclaredThrowableException =>
                log.warn(s"[keepinterner] Persisting keep failed of ${keep.url} (${keep.id.get})", ex)
                throw ex.getUndeclaredThrowable
            }
          }
        (false, wasInactiveKeep, savedKeep)
      case None =>
        val keep = Keep(
          title = trimmedTitle orElse uri.title,
          userId = userId,
          uriId = uri.id.get,
          url = url,
          source = source,
          visibility = libraryOpt.map(_.visibility).getOrElse(LibraryVisibility.SECRET),
          libraryId = libraryOpt.map(_.id.get),
          keptAt = keptAt,
          note = note,
          originalKeeperId = Some(userId),
          organizationId = libraryOpt.flatMap(_.organizationId),
          connections = KeepConnections(libraryOpt.map(_.id.get).toSet[Id[Library]], Set(userId))
        )
        val improvedKeep = try {
          keepCommander.persistKeep(integrityHelpers.improveKeepSafely(uri, keep)) tap { improvedKeep =>
            sourceAttribution.map { attr => sourceAttrRepo.save(improvedKeep.id.get, attr) }
          }
        } catch {
          case ex: UndeclaredThrowableException =>
            log.warn(s"[keepinterner] Persisting keep failed of ${keep.url}", ex.getUndeclaredThrowable)
            throw ex.getUndeclaredThrowable
        }

        (true, false, improvedKeep)
    }

    (isNewKeep, wasInactiveKeep, internedKeep)
  }

  private def updateKeepTagsUsingNote(keeps: Seq[Keep]) = {
    def tryFixKeepNote(keep: Keep)(implicit session: RWSession) = {
      Try(keepCommander.updateKeepNote(keep.userId, keep, keep.note.getOrElse("")))
    }

    // Attach tags to keeps
    val res = db.readWriteBatch(keeps, attempts = 5) {
      case ((s, keep)) =>
        if (keep.note.nonEmpty) {
          tryFixKeepNote(keep)(s) // Don't blow if something messes up, try our best to just get through
        } else Success(keep)
    }.map { k =>
      (k._1, k._2.flatten)
    }

    res.collect {
      case ((k, Failure(ex))) => (k.id.get, ex)
    }.foreach {
      case (keepId, failure: UndeclaredThrowableException) =>
        log.warn(s"[keepinterner] Failed updating keep note for $keepId", failure.getUndeclaredThrowable)
      case (keepId, failure) =>
        log.warn(s"[keepinterner] Failed updating keep note for $keepId", failure)
    }
  }

  private def reportNewKeeps(keeperUserId: Id[User], keeps: Seq[Keep], libraryOpt: Option[Library], ctx: HeimdalContext, notifyExternalSources: Boolean): Unit = {
    if (keeps.nonEmpty) {
      // Analytics
      libraryOpt.foreach { lib => libraryAnalytics.keptPages(keeperUserId, keeps, lib, ctx) }
      heimdalClient.processKeepAttribution(keeperUserId, keeps)
      searchClient.updateKeepIndex()

      // Make external notifications & fetch
      if (notifyExternalSources && KeepSource.discrete.contains(keeps.head.source)) { // Only report first to not spam
        SafeFuture {
          libraryOpt.foreach { lib =>
            libraryNewFollowersCommander.notifyFollowersOfNewKeeps(lib, keeps.head)
            libToSlackProcessor.schedule(lib.id.get)
          }
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
        libraryOpt.foreach { lib =>
          libraryRepo.updateLastKept(lib.id.get)
          Try(libraryRepo.save(libraryRepo.getNoCache(lib.id.get).copy(keepCount = keepRepo.getCountByLibrary(lib.id.get)))) // wrapped in a Try because this is super deadlock prone
        }
      }
    }
  }

}
