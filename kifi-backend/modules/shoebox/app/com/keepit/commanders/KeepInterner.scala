package com.keepit.commanders

import java.lang.reflect.UndeclaredThrowableException
import java.util.UUID

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.{ ReactiveLock, FutureHelpers, ExecutionContext }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.store.S3ImageConfig
import com.keepit.eliza.ElizaServiceClient
import com.keepit.search.SearchServiceClient
import com.keepit.shoebox.data.assemblers.KeepActivityAssembler
import com.keepit.social.Author
import com.keepit.typeahead.{ LibraryResultTypeaheadKey, LibraryResultTypeaheadCache }
import scala.concurrent.Future
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

final case class KeepInternRequest(
    author: Author,
    url: String,
    source: KeepSource,
    attribution: RawSourceAttribution,
    title: Option[String],
    note: Option[String],
    keptAt: Option[DateTime],
    recipients: KeepRecipients) {
  def trimmedTitle = title.map(_.trim).filter(_.nonEmpty)
}
object KeepInternRequest {
  def onKifi(keeper: Id[User], recipients: KeepRecipients, url: String, source: KeepSource, title: Option[String], note: Option[String], keptAt: Option[DateTime]): KeepInternRequest =
    KeepInternRequest(
      author = Author.KifiUser(keeper),
      url = url,
      source = source,
      attribution = RawKifiAttribution(keeper, note, recipients.plusUser(keeper), source),
      title = title,
      note = note,
      keptAt = keptAt,
      recipients = recipients.plusUser(keeper)
    )
}
final case class KeepInternResponse(newKeeps: Seq[Keep], existingKeeps: Seq[Keep], failures: Seq[RawBookmarkRepresentation]) {
  def successes = newKeeps ++ existingKeeps
}

@ImplementedBy(classOf[KeepInternerImpl])
trait KeepInterner {
  // This is a modern API that supports multiple libraries, use it preferentially when possible
  def internKeepByRequest(req: KeepInternRequest)(implicit session: RWSession, context: HeimdalContext): Try[(Keep, Boolean)]

  // This is an older API that supports [0,1] libraries
  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersAdded: Set[Id[User]], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse

  // Because we're nice, you get some extras:
  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): (Seq[Keep], Seq[RawBookmarkRepresentation]) = {
    val response = internRawBookmarksWithStatus(rawBookmarks, Some(ownerId), Some(library), Set.empty, source)
    (response.successes, response.failures)
  }
  def internRawBookmark(rawBookmark: RawBookmarkRepresentation, ownerId: Id[User], library: Library, source: KeepSource)(implicit context: HeimdalContext): Try[(Keep, Boolean)] = {
    internRawBookmarksWithStatus(Seq(rawBookmark), Some(ownerId), Some(library), Set.empty, source) match {
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
    ktuRepo: KeepToUserRepo,
    libraryRepo: LibraryRepo,
    keepMutator: KeepMutator,
    airbrake: AirbrakeNotifier,
    libraryAnalytics: LibraryAnalytics,
    userValueRepo: UserValueRepo,
    heimdalClient: HeimdalServiceClient,
    roverClient: RoverServiceClient,
    eliza: ElizaServiceClient,
    libraryNewFollowersCommander: LibraryNewKeepsCommander,
    integrityHelpers: UriIntegrityHelpers,
    sourceAttrRepo: KeepSourceAttributionRepo,
    keepSourceCommander: KeepSourceCommander,
    keepActivityAssembler: KeepActivityAssembler,
    libToSlackProcessor: LibraryToSlackChannelPusher,
    userInteractionCommander: UserInteractionCommander,
    normalizationService: NormalizationService,
    searchClient: SearchServiceClient,
    libResCache: LibraryResultTypeaheadCache,
    relevantSuggestedLibrariesCache: RelevantSuggestedLibrariesCache,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices,
    implicit private val imageConfig: S3ImageConfig) extends KeepInterner with Logging {

  private val httpPrefix = "https?://".r
  implicit private val fj = ExecutionContext.fj

  private def getKeepToInternWith(uriId: Id[NormalizedURI], connections: KeepRecipients)(implicit session: RSession): Option[Keep] = {
    val candidates = if (connections.libraries.nonEmpty) {
      keepRepo.getByUriAndLibrariesHash(uriId, connections.libraries)
    } else {
      keepRepo.getByUriAndParticipantsHash(uriId, connections.users, connections.emails)
    }
    candidates.maxByOpt { keep => (keep.recipients == connections, keep.lastActivityAt, keep.id.get) }
  }
  @StatsdTiming("KeepInterner.internKeepByRequest")
  def internKeepByRequest(internReq: KeepInternRequest)(implicit session: RWSession, context: HeimdalContext): Try[(Keep, Boolean)] = {
    val urlIsCompletelyUnusable = httpPrefix.findPrefixOf(internReq.url.toLowerCase).isEmpty || normalizationService.prenormalize(internReq.url).isFailure
    if (urlIsCompletelyUnusable) Failure(KeepFail.MALFORMED_URL)
    else {
      val uri = normalizedURIInterner.internByUri(internReq.url, contentWanted = true, candidates = Set.empty)
      val userIdOpt = Author.kifiUserId(internReq.author)
      getKeepToInternWith(uri.id.get, internReq.recipients) match {
        case Some(existingKeep) =>
          val updatedKeep = {
            val keepWithNewRecipients = keepMutator.unsafeModifyKeepRecipients(
              existingKeep.id.get,
              (internReq.recipients -- existingKeep.recipients).onlyAdditions,
              userAttribution = userIdOpt
            )
            (Author.kifiUserId(internReq.author), internReq.title) match {
              case (Some(kifiUser), Some(newTitle)) if KeepSource.manual.contains(internReq.source) =>
                keepMutator.updateKeepTitle(keepWithNewRecipients, newTitle)
              case _ => keepWithNewRecipients
            }
          }
          Success((updatedKeep, false))
        case None =>
          val keptAt = internReq.keptAt.getOrElse(clock.now)
          val keep = Keep(
            title = internReq.trimmedTitle orElse uri.title,
            userId = userIdOpt,
            uriId = uri.id.get,
            url = internReq.url,
            source = internReq.source,
            keptAt = keptAt,
            note = None,
            originalKeeperId = userIdOpt,
            recipients = internReq.recipients,
            lastActivityAt = keptAt
          )
          val newKeep = try {
            keepMutator.persistBrandNewKeep(integrityHelpers.improveKeepSafely(uri, keep)) tap { improvedKeep =>
              sourceAttrRepo.intern(improvedKeep.id.get, internReq.attribution)
            }
          } catch {
            case ex: UndeclaredThrowableException =>
              log.warn(s"[keepinterner] Persisting keep failed of ${keep.url}", ex.getUndeclaredThrowable)
              throw ex.getUndeclaredThrowable
          }

          val libs = libraryRepo.getActiveByIds(newKeep.recipients.libraries)
          session.onTransactionSuccess {
            reportNewKeeps(Seq(newKeep), libs.traverseByKey, context)
          }
          Success((newKeep, false))
      }
    }
  }

  def internRawBookmarksWithStatus(rawBookmarks: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersAdded: Set[Id[User]], source: KeepSource)(implicit context: HeimdalContext): KeepInternResponse = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, ownerIdOpt, libraryOpt, usersAdded, source)

    val (newKeeps, existingKeeps) = persistedBookmarksWithUris.partition(obj => obj.isNewKeep || obj.wasInactiveKeep) match {
      case (newKs, existingKs) => (newKs.map(_.keep), existingKs.map(_.keep))
    }

    reportNewKeeps(newKeeps, libraryOpt.toSeq, context)

    KeepInternResponse(newKeeps, existingKeeps, failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersAdded: Set[Id[User]], source: KeepSource) = {
    // For batches, if we can't prenormalize, silently drop. This is a low bar, and usually means it couldn't *ever* be a valid keep.
    val (validUrls, invalidUrls) = bms.partition(b => httpPrefix.findPrefixOf(b.url.toLowerCase).isDefined && normalizationService.prenormalize(b.url).isSuccess)

    val (persisted, failed) = db.readWriteBatch(validUrls, attempts = 5) {
      case ((session, bm)) =>
        implicit val s = session
        internUriAndBookmark(bm, ownerIdOpt, libraryOpt, usersAdded, source).get // Exception caught by readWriteBatch
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

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, ownerIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersAdded: Set[Id[User]], source: KeepSource)(implicit session: RWSession): Try[InternedUriAndKeep] = try {
    if (httpPrefix.findPrefixOf(rawBookmark.url.toLowerCase).isDefined) {
      val uri = try {
        normalizedURIInterner.internByUri(rawBookmark.url, contentWanted = true, candidates = NormalizationCandidate.fromRawBookmark(rawBookmark))
      } catch {
        case t: Throwable => throw new Exception(s"error persisting raw bookmark $rawBookmark for user $ownerIdOpt, from $source", t)
      }

      log.info(s"[keepinterner] Persisting keep ${rawBookmark.url}, ${rawBookmark.keptAt}, ${clock.now}")
      val (isNewKeep, wasInactiveKeep, bookmark) = internKeep(uri, ownerIdOpt, libraryOpt, usersAdded, source, rawBookmark.title, rawBookmark.url, rawBookmark.keptAt.getOrElse(clock.now), rawBookmark.sourceAttribution, rawBookmark.noteFormattedLikeOurNotes)
      Success(InternedUriAndKeep(bookmark, uri, isNewKeep, wasInactiveKeep))
    } else {
      Failure(new Exception(s"bookmark url is not an http protocol: ${rawBookmark.url}"))
    }
  } catch {
    case e: Throwable =>
      Failure(e)
  }

  @StatsdTiming("KeepInterner.internKeep")
  private def internKeep(uri: NormalizedURI, userIdOpt: Option[Id[User]], libraryOpt: Option[Library], usersAdded: Set[Id[User]], source: KeepSource,
    title: Option[String], url: String, keptAt: DateTime,
    thirdPartyAttribution: Option[RawSourceAttribution], note: Option[String])(implicit session: RWSession) = {
    airbrake.verify(userIdOpt.isDefined || thirdPartyAttribution.isDefined, s"interning a keep (uri ${uri.id.get}, lib ${libraryOpt.map(_.id.get)}) with no user AND no source?!?!?!")

    val sourceAttribution = thirdPartyAttribution.orElse(userIdOpt.map(userId => RawKifiAttribution(userId, note, KeepRecipients(libraryOpt.map(_.id.get).toSet, Set.empty, usersAdded), source)))

    val recipients = KeepRecipients(libraryOpt.map(_.id.get).toSet, Set.empty, userIdOpt.toSet ++ usersAdded)
    val existingKeepOpt = getKeepToInternWith(uri.id.get, recipients)
    existingKeepOpt match {
      case Some(existingKeep) =>
        val internedKeep = {
          val keepWithNewRecipients = keepMutator.unsafeModifyKeepRecipients(
            existingKeep.id.get,
            (recipients -- existingKeep.recipients).onlyAdditions,
            userAttribution = userIdOpt
          )
          (userIdOpt, title) match {
            case (Some(kifiUser), Some(newTitle)) if KeepSource.manual.contains(source) =>
              keepRepo.save(keepWithNewRecipients.withTitle(Some(newTitle)).withNote(note))
            case _ => keepWithNewRecipients
          }
        }
        val wasInactiveKeep = false
        val isNewKeep = false
        (isNewKeep, wasInactiveKeep, internedKeep)
      case None =>
        val keep = Keep(
          title = title.map(_.trim).filter(_.nonEmpty) orElse uri.title,
          userId = userIdOpt,
          uriId = uri.id.get,
          url = url,
          source = source,
          keptAt = keptAt,
          note = note.map(_.trim).filter(_.nonEmpty),
          originalKeeperId = existingKeepOpt.flatMap(_.userId) orElse userIdOpt,
          recipients = recipients,
          lastActivityAt = keptAt
        )
        val internedKeep = try {
          keepMutator.persistBrandNewKeep(integrityHelpers.improveKeepSafely(uri, keep), libraryOpt.toSeq) tap { improvedKeep =>
            sourceAttribution.foreach { attr => sourceAttrRepo.intern(improvedKeep.id.get, attr) }
          }
        } catch {
          case ex: UndeclaredThrowableException =>
            log.warn(s"[keepinterner] Persisting keep failed of ${keep.url}", ex.getUndeclaredThrowable)
            throw ex.getUndeclaredThrowable
        }

        val wasInactiveKeep = false // we do not resurrect dead keeps anymore
        val isNewKeep = true
        (isNewKeep, wasInactiveKeep, internedKeep)
    }
  }

  private def updateKeepTagsUsingNote(keeps: Seq[Keep]) = {
    def tryFixKeepNote(keep: Keep)(implicit session: RWSession) = {
      Try(keepMutator.updateKeepNote(keep.userId.get, keep, keep.note.getOrElse(""))) // will throw if keep.userId.isEmpty
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

  private val reportingLock = new ReactiveLock(2)
  private def reportNewKeeps(keeps: Seq[Keep], libraries: Seq[Library], ctx: HeimdalContext): Unit = {
    if (keeps.nonEmpty) {
      // Don't block keeping for these
      log.info(s"[reportNewKeeps] Data updates for import ${reportingLock.waiting}")
      reportingLock.withLockFuture {
        SafeFuture {
          // Analytics & typeaheads
          libraries.foreach { lib => libraryAnalytics.keptPages(keeps, lib, ctx) }
          keeps.groupBy(_.userId).collect {
            case (Some(userId), _) =>
              libraries.foreach { lib =>
                libResCache.direct.remove(LibraryResultTypeaheadKey(userId, lib.id.get))
                relevantSuggestedLibrariesCache.direct.remove(RelevantSuggestedLibrariesKey(userId))
              }
          }
          searchClient.updateKeepIndex()

          // Make external notifications & fetch
          libraries.foreach { lib =>
            libraryNewFollowersCommander.notifyFollowersOfNewKeeps(lib, keeps)
            libToSlackProcessor.schedule(Set(lib.id.get))
            if (KeepSource.manual.contains(keeps.head.source)) {
              keeps.groupBy(_.userId).keySet.flatten.foreach { userId =>
                userInteractionCommander.addInteractions(userId, Seq(LibraryInteraction(lib.id.get) -> UserInteraction.KEPT_TO_LIBRARY))
              }
            }
          }
          val keepsToNotifyAbout = keeps.filter(_.recipients.users.size > 1)
          val keepIdsToNotifyAbout = keepsToNotifyAbout.map(_.id.get).toSet
          val (ktls, ktus, sourceAttrs) = db.readOnlyMaster { implicit s =>
            val ktls = ktlRepo.getAllByKeepIds(keepIdsToNotifyAbout)
            val ktus = ktuRepo.getAllByKeepIds(keepIdsToNotifyAbout)
            val sourceAttrs = keepSourceCommander.getSourceAttributionForKeeps(keepIdsToNotifyAbout)
            (ktls, ktus, sourceAttrs)
          }
          val basicKeeps = { keepActivityAssembler.assembleInitialEventsForKeeps(keepsToNotifyAbout, sourceAttrs, ktls, ktus) }
          FutureHelpers.sequentialExec(keepsToNotifyAbout) { keep =>
            val basicKeep = basicKeeps.get(keep.id.get)
            keep.userId.map(uid => eliza.modifyRecipientsAndSendEvent(keep.id.get, uid, keep.recipients.toDiff, basicKeep.flatMap(_.source.map(_.kind)), basicKeep)).getOrElse(Future.successful(()))
          }

          // Prefer discrete, recent keeps. Take 20 best that are within the past 14 days.
          val fastfetchKeeps = keeps
            .sortBy(k => (KeepSource.discrete.contains(k.source), k.keptAt))(implicitly[Ordering[(Boolean, DateTime)]].reverse)
            .take(20)
            .filter(_.keptAt.isAfter(clock.now.minusDays(14)))
          if (fastfetchKeeps.nonEmpty) {
            FutureHelpers.sequentialExec(fastfetchKeeps) { keep =>
              val nuri = db.readOnlyMaster { implicit session =>
                normalizedURIRepo.get(keep.uriId)
              }
              roverClient.fetchAsap(nuri.id.get, nuri.url)
            }
          }

          // Update data-dependencies
          db.readWrite(attempts = 3) { implicit s =>
            libraries.foreach { lib =>
              libraryRepo.updateLastKept(lib.id.get)
              Try(libraryRepo.save(libraryRepo.getNoCache(lib.id.get).copy(keepCount = ktlRepo.getCountByLibraryId(lib.id.get)))) // wrapped in a Try because this is super deadlock prone
            }
          }
        }
      }
    }
  }

}
