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

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersOpt: Option[Set[Id[User]]], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse

  // Because we're nice, you get some extras:
  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val response = internRawBookmarksWithStatus(rawBookmarks, Some(ownerId), Some(library), None, source)
    (response.successes, response.failures)
  }
  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)] = {
    internRawBookmarksWithStatus(Seq(rawBookmark), Some(ownerId), Some(library), None, source) match {
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
  ktlRepo: KeepToLibraryRepo,
  libraryRepo: LibraryRepo,
  keepCommander: KeepCommander,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  airbrake: AirbrakeNotifier,
  libraryAnalytics: LibraryAnalytics,
  userValueRepo: UserValueRepo,
  heimdalClient: HeimdalServiceClient,
  roverClient: RoverServiceClient,
  libraryNewFollowersCommander: LibraryNewKeepsCommander,
  integrityHelpers: UriIntegrityHelpers,
  sourceAttrRepo: KeepSourceAttributionRepo,
  libToSlackProcessor: LibraryToSlackChannelPusher,
  userInteractionCommander: UserInteractionCommander,
  normalizationService: NormalizationService,
  searchClient: SearchServiceClient,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends KeepInterner with Logging {

  implicit private val fj = ExecutionContext.fj

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersOpt: Option[Set[Id[User]]], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, ownerIdOpt, libraryOpt, usersOpt, source)

    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKs, existingKs) => (newKs.map(_.keep), existingKs.map(_.keep))
    }

    reportNewKeeps(newKeeps, libraryOpt, context, notifyExternalSources = true)

    KeepInternResponse(newKeeps, existingKeeps, failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersOpt: Option[Set[Id[User]]], source: KeepSource) = {
    // For batches, if we can't prenormalize, silently drop. This is a low bar, and usually means it couldn't *ever* be a valid keep.
    val (validUrls, invalidUrls) = bms.partition(b => httpPrefix.findPrefixOf(b.url.toLowerCase).isDefined && normalizationService.prenormalize(b.url).isSuccess)

    val (persisted, failed) = db.readWriteBatch(validUrls, attempts = 5) {
      case ((session, bm)) =>
        implicit val s = session
        internUriAndBookmark(bm, ownerIdOpt, libraryOpt, usersOpt, source).get // Exception caught by readWriteBatch
    }.partition { case (bm, res) => res.isSuccess }

    val keeps = persisted.values.map(_.get).toSeq

    updateKeepTagsUsingNote(keeps.map(_.keep).filter(_.note.exists(_.nonEmpty)))

    if (failed.nonEmpty) {
      airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks (${validUrls.size} valid): look app.log for urls"), userId = ownerIdOpt))
      failed.foreach { f => log.warn(s"failed to persist raw bookmarks of user $ownerIdOpt from $source: ${f._1.url}", f._2.failed.get) }
    }
    val failedRaws: Seq[RawBookmarkRepresentation] = failed.keys.toList ++ invalidUrls
    (keeps, failedRaws)
  }

  private val httpPrefix = "https?://".r

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersOpt: Option[Set[Id[User]]], source: KeepSource)(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, contentWanted = true, candidates = NormalizationCandidate.fromRawBookmark(rawBookmark))
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $ownerIdOpt, from $source", t)
      }

      log.info(s"[keepinterner] Persisting keep ${rawBookmark.url}, ${rawBookmark.keptAt}, ${clock.now}")
      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, ownerIdOpt, libraryOpt, usersOpt, source, rawBookmark.title, rawBookmark.url, rawBookmark.keptAt.getOrElse(clock.now), rawBookmark.sourceAttribution, rawBookmark.noteFormattedLikeOurNotes)
      Success(InternedUriAndKeep(bookmark, uri, isNewKeep, wasInactiveKeep))
    } else {
      Failure(new Exception(s"bookmark url is not an http protocol: ${rawBookmark.url}"))
    }
  } catch {
    case e: Throwable =>
      Failure(e)
  }

  private def internKeep(uri: NormalizedURI, userIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersOpt: Option[Set[Id[User]]], source: KeepSource,
    title: Option[String], url: String, keptAt: DateTime,
    sourceAttribution: Option[RawSourceAttribution], note: Option[String])(implicit session: RWSession) = {
    airbrake.verify(userIdOpt.isDefined || sourceAttribution.isDefined, s"interning a keep (uri ${uri.id.get}, lib ${libraryOpt.map(_.id.get)}) with no user AND no source?!?!?!")

    val existingKeepOpt = libraryOpt.flatMap { lib => keepRepo.getByUriAndLibrariesHash(uri.id.get, Set(lib.id.get)).headOption }

    val kTitle = List(title.map(_.trim).filter(_.nonEmpty), existingKeepOpt.flatMap(_.title), uri.title).flatten.headOption
    val kNote = List(note.map(_.trim).filter(_.nonEmpty), existingKeepOpt.flatMap(_.note)).flatten.headOption
    val keep = Keep(
      id = existingKeepOpt.map(_.id.get),
      externalId = existingKeepOpt.map(_.externalId).getOrElse(ExternalId()),
      title = kTitle,
      userId = userIdOpt,
      uriId = uri.id.get,
      url = url,
      source = source,
      keptAt = existingKeepOpt.map(_.keptAt).getOrElse(keptAt),
      note = kNote,
      originalKeeperId = existingKeepOpt.flatMap(_.userId) orElse userIdOpt,
      connections = KeepConnections(libraryOpt.map(_.id.get).toSet[Id[Library]], userIdOpt.toSet),
      lastActivityAt = existingKeepOpt.map(_.lastActivityAt).getOrElse(keptAt),
      initialEvent = Some(existingKeepOpt.flatMap(_.initialEvent).getOrElse(KeepEvent.InitialKeep(userIdOpt, usersOpt.getOrElse(Set.empty), Set.empty, libraryOpt.map(_.id.get).toSet)))
    )
    val internedKeep = try {
      keepCommander.persistKeep(integrityHelpers.improveKeepSafely(uri, keep)) tap { improvedKeep =>
        sourceAttribution.foreach { attr => sourceAttrRepo.intern(improvedKeep.id.get, attr) }
      }
    } catch {
      case ex: UndeclaredThrowableException =>
        log.warn(s"[keepinterner] Persisting keep failed of ${keep.url}", ex.getUndeclaredThrowable)
        throw ex.getUndeclaredThrowable
    }

    val wasInactiveKeep = false // we do not resurrect dead keeps anymore
    val isNewKeep = existingKeepOpt.isEmpty
    (isNewKeep, wasInactiveKeep, internedKeep)
  }

  private def updateKeepTagsUsingNote(keeps: Seq[Keep]) = {
    def tryFixKeepNote(keep: Keep)(implicit session: RWSession) = {
      Try(keepCommander.updateKeepNote(keep.userId.get, keep, keep.note.getOrElse(""))) // will throw if keep.userId.isEmpty
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

  private def reportNewKeeps(keeps: Seq[Keep], libraryOpt: Option[Library], ctx: HeimdalContext, notifyExternalSources: Boolean): Unit = {
    if (keeps.nonEmpty) {
      // Analytics
      libraryOpt.foreach { lib => libraryAnalytics.keptPages(keeps, lib, ctx) }
      keeps.groupBy(_.userId).collect { case (Some(userId), ks) => heimdalClient.processKeepAttribution(userId, ks) }
      searchClient.updateKeepIndex()

      // Make external notifications & fetch
      if (notifyExternalSources) { // Only report first to not spam
        SafeFuture {
          libraryOpt.foreach { lib =>
            libraryNewFollowersCommander.notifyFollowersOfNewKeeps(lib, keeps)
            libToSlackProcessor.schedule(Set(lib.id.get))
            if (KeepSource.manual.contains(keeps.head.source)) {
              keeps.groupBy(_.userId).keySet.flatten.foreach { userId =>
                userInteractionCommander.addInteractions(userId, Seq(LibraryInteraction(lib.id.get) -> UserInteraction.KEPT_TO_LIBRARY))
              }
            }
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
          Try(libraryRepo.save(libraryRepo.getNoCache(lib.id.get).copy(keepCount = ktlRepo.getCountByLibraryId(lib.id.get)))) // wrapped in a Try because this is super deadlock prone
        }
      }
    }
  }

}
