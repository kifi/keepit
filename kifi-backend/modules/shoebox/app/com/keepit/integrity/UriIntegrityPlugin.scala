package com.keepit.integrity

import akka.pattern.{ ask, pipe }
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.commanders.{ KeepMutator, KeepCommander, KeepToUserCommander, KeepToLibraryCommander }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.time._
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.rover.fetcher.HttpRedirect
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scala.concurrent.duration._

case class BatchURIMigration(batchSize: Int)
case class FixDuplicateKeeps()

class UriIntegrityActor @Inject() (
    val db: Database,
    clock: Clock,
    val normUriRepo: NormalizedURIRepo,
    normalizedURIInterner: NormalizedURIInterner,
    val keepRepo: KeepRepo,
    keepMutator: KeepMutator,
    changedUriRepo: ChangedURIRepo,
    centralConfig: CentralConfig,
    val airbrake: AirbrakeNotifier,
    helpers: UriIntegrityHelpers) extends FortyTwoActor(airbrake) with UriIntegrityChecker with Logging {

  /** tricky point: make sure (library, uri) pair is unique.  */
  private def handleBookmarks(oldKeepIds: Set[Id[Keep]])(implicit session: RWSession): Unit = {
    var urlToUriMap: Map[String, NormalizedURI] = Map()

    oldKeepIds.foreach { keepId =>
      val keep = keepRepo.getNoCache(keepId)
      val newUri = urlToUriMap.getOrElse(keep.url, {
        val newUri = normalizedURIInterner.internByUri(keep.url, contentWanted = true)
        urlToUriMap += (keep.url -> newUri)
        newUri
      })

      if (newUri.state == NormalizedURIStates.REDIRECTED) {
        // skipping due to double redirects. this should not happen.
        log.error(s"double uri redirect found: keepId=${keep.id.get} uriId=${newUri.id.get}")
        airbrake.notify(s"double uri redirect found: keepId=${keep.id.get} uriId=${newUri.id.get}")
        (None, None)
      } else {
        keepMutator.changeUri(keep, newUri)
      }
    }
  }

  /**
   * Any reference to the old uri should be redirected to the new one.
   * NOTE: We have 1-1 mapping from entities to url, the only exception (as of writing) is normalizedUri-url mapping, which is 1 to n.
   * A migration from uriA to uriB is (almost) equivalent to N url to uriB migrations, where the N urls are currently associated with uriA.
   */
  private def handleURIMigration(change: ChangedURI): Unit = {
    val t1 = System.currentTimeMillis()
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) {
      if (oldUriId == newUriId) {
        db.readWrite { implicit s =>
          changedUriRepo.saveWithoutIncreSeqnum(change.withState(ChangedURIStates.INACTIVE))
        }
      }
    } else {
      db.readWrite(attempts = 3) { implicit s =>
        updateNewUri(oldUriId, newUriId)
      }

      // fix up redirections
      val previouslyRedirectedUris = db.readWrite { implicit s =>
        normUriRepo.getByRedirection(oldUriId)
      }
      db.readWriteSeq(previouslyRedirectedUris) { (s, uri) =>
        normUriRepo.save(uri.withRedirect(newUriId, currentDateTime))(s)
      }
      db.readWrite(attempts = 3) { implicit s =>
        val oldUri = normUriRepo.get(oldUriId)
        normUriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))
      }

      val bms = db.readOnlyMaster { implicit s =>
        keepRepo.getByUri(oldUriId, excludeState = None)
      }
      // process keeps for each user
      bms.groupBy(_.userId).foreach {
        case (_, keeps) =>
          db.readWrite(attempts = 3) { implicit s => handleBookmarks(keeps.map(_.id.get).toSet) }
      }

      // some additional sanity check right away!
      db.readWrite(attempts = 3) { implicit s =>
        checkIntegrity(newUriId, readOnly = false, hasKnownKeep = bms.nonEmpty)
      }

      db.readWrite(attempts = 3) { implicit s =>
        changedUriRepo.saveWithoutIncreSeqnum(change.withState(ChangedURIStates.APPLIED))
      }
    }

    val t2 = System.currentTimeMillis()
    log.info(s"one uri migration from $oldUriId to $newUriId takes ${t2 - t1} millis")
  }

  private def updateNewUri(oldUriId: Id[NormalizedURI], newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    val uri = normUriRepo.get(newUriId)
    val updatedState = if (uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED) NormalizedURIStates.ACTIVE else uri.state
    val contentWanted = uri.shouldHaveContent || normUriRepo.get(oldUriId).shouldHaveContent
    val updatedUri = uri.withState(updatedState).withContentRequest(contentWanted).copy(redirect = None, redirectTime = None)
    if (uri != updatedUri) normUriRepo.save(updatedUri)
  }

  private def batchURIMigration(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pairs of uris to be merged")

    if (toMerge.isEmpty) {
      log.debug("no active changed_uris were founded. Check if we have applied changed_uris generated during urlRenormalization")
      val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
      val applied = db.readOnlyReplica { implicit s => changedUriRepo.getChangesSince(lowSeq, batchSize, state = ChangedURIStates.APPLIED) }
      if (applied.size == batchSize) { // make sure a full batch of applied ones
        applied.sortBy(_.seq).lastOption.foreach { x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
        log.info(s"${applied.size} applied changed_uris are found!")
      }
      return applied.size
    }

    toMerge.foreach { change =>
      try {
        handleURIMigration(change)
      } catch {
        case e: Exception =>
          airbrake.notify(s"Exception in migrating uri ${change.oldUriId} to ${change.newUriId}. Going to delete them from cache", e)

          try {
            db.readOnlyMaster { implicit s => List(normUriRepo.get(change.oldUriId), normUriRepo.get(change.newUriId)) foreach { normUriRepo.deleteCache } }
            db.readWrite { implicit s => changedUriRepo.save(change.withState(ChangedURIStates.ACTIVE)) } // bump up seqNum. Will be retried.
          } catch {
            case e: Exception =>
              db.readWrite { implicit s => changedUriRepo.saveWithoutIncreSeqnum(change.withState(ChangedURIStates.FAILED)) } // failed. Not bumping up seqNum. Not retry actively.
              airbrake.notify(s"error in getting uri ${change.oldUriId} or ${change.newUriId} from db by id.")
          }
      }
    }

    toMerge.sortBy(_.seq).lastOption.foreach { x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pairs of uris merged. zookeeper seqNum updated.")
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    log.info(s"batch uri migration: fetching tasks from seqNum $lowSeq")
    db.readOnlyReplica { implicit s => changedUriRepo.getChangesSince(lowSeq, fetchSize, state = ChangedURIStates.ACTIVE) }
  }

  private def fixDuplicateKeeps(): Unit = {
    val seq = centralConfig(FixDuplicateKeepsSeqNumKey) getOrElse SequenceNumber.ZERO

    log.debug(s"start deduping keeps: fetching tasks from seqNum $seq")
    try {
      var dedupedSuccessCount = 0
      val keeps = db.readOnlyReplica { implicit s => keepRepo.getBookmarksChanged(seq, 30) }
      if (keeps.nonEmpty) {
        db.readWriteBatch(keeps, 3) { (session, keep) =>
          val newUriOpt = normalizedURIInterner.getByUri(keep.url)(session)
          if (newUriOpt.forall(_.id.get != keep.uriId)) {
            log.info(s"fix keep [id=${keep.id.get}, oldUriId=${keep.uriId}, newUriId=${newUriOpt.map(_.toShortString).getOrElse("missing")}]")
            handleBookmarks(Set(keep.id.get))(session)
            dedupedSuccessCount += 1
          }
        }
        log.info(s"keeps deduped [count=$dedupedSuccessCount/${keeps.size}]")
        if (seq == centralConfig(FixDuplicateKeepsSeqNumKey).getOrElse(SequenceNumber.ZERO)) {
          centralConfig.update(FixDuplicateKeepsSeqNumKey, keeps.last.seq)
        }
      }
      log.info(s"total $dedupedSuccessCount keeps deduped")
    } catch {
      case e: Throwable => log.error("deduping keeps failed", e)
    }
  }

  def receive = {
    case BatchURIMigration(batchSize) => Future.successful(batchURIMigration(batchSize)) pipeTo sender
    case FixDuplicateKeeps() => fixDuplicateKeeps()
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulerPlugin {
  def batchURIMigration(batchSize: Int = -1): Future[Int]
  def setFixDuplicateKeepsSeq(seq: Long): Unit
  def clearRedirects(toUriId: Id[NormalizedURI]): Unit
}

@Singleton
class UriIntegrityPluginImpl @Inject() (
    actor: ActorInstance[UriIntegrityActor],
    db: Database,
    uriRepo: NormalizedURIRepo,
    centralConfig: CentralConfig,
    val scheduling: SchedulingProperties) extends UriIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() { //keep me alive!
    scheduleTaskOnOneMachine(actor.system, 1 hours, 12 hours, actor.ref, BatchURIMigration(100), BatchURIMigration.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 2 hours, 12 hours, actor.ref, FixDuplicateKeeps(), FixDuplicateKeeps.getClass.getSimpleName)
  }

  def batchURIMigration(batchSize: Int) = actor.ref.ask(BatchURIMigration(batchSize))(1 minute).mapTo[Int]

  def setFixDuplicateKeepsSeq(seq: Long): Unit = { centralConfig.update(FixDuplicateKeepsSeqNumKey.longKey, seq) }

  def clearRedirects(toUriId: Id[NormalizedURI]): Unit = {
    db.readWrite { implicit s =>
      val uris = uriRepo.getByRedirection(toUriId)
      uris.foreach { uri =>
        log.info(s"clearing redirect [id=${uri.id.get} url=${uri.url}]")
        uriRepo.save(uri.copy(redirect = None, redirectTime = None, normalization = None, state = NormalizedURIStates.ACTIVE))
      }
      log.info(s"redirect cleared [count=${uris.size}]")
    }
  }
}

@Singleton
class UriIntegrityHelpers @Inject() (keepRepo: KeepRepo) extends Logging {
  def improveKeepSafely(uri: NormalizedURI, keep: Keep): Keep = {
    require(keep.uriId == uri.id.get, "URI and Keep don't match.")
    val keepWithTitle = if (keep.title.isEmpty) keep.withTitle(uri.title) else keep
    if (HttpRedirect.isShortenedUrl(keepWithTitle.url)) {
      keepWithTitle.copy(url = uri.url)
    } else keepWithTitle
  }

  def improveKeepsSafely(uri: NormalizedURI)(implicit session: RWSession): Unit = {
    keepRepo.getByUri(uri.id.get).foreach { keep =>
      val betterKeep = improveKeepSafely(uri, keep)
      if (betterKeep != keep) {
        log.info(s"Saving improved keep $betterKeep over original keep $keep relying on uri $uri")
        keepRepo.save(betterKeep)
      }
    }
  }
}
