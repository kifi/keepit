package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.actor.ActorInstance
import com.keepit.rover.fetcher.HttpRedirect
import scala.concurrent.duration._
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.RWSession
import akka.pattern.{ ask, pipe }
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.common.core._

trait UriChangeMessage

case class URIMigration(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends UriChangeMessage
case class URLMigration(url: URL, newUri: Id[NormalizedURI]) extends UriChangeMessage
case class BatchURIMigration(batchSize: Int)
case class BatchURLMigration(batchSize: Int)
case class FixDuplicateKeeps()

class UriIntegrityActor @Inject() (
    val db: Database,
    clock: Clock,
    val normUriRepo: NormalizedURIRepo,
    normalizedURIInterner: NormalizedURIInterner,
    urlRepo: URLRepo,
    val keepRepo: KeepRepo,
    changedUriRepo: ChangedURIRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    renormRepo: RenormalizedURLRepo,
    centralConfig: CentralConfig,
    val airbrake: AirbrakeNotifier,
    keepUriUserCache: KeepUriUserCache,
    helpers: UriIntegrityHelpers) extends FortyTwoActor(airbrake) with UriIntegrityChecker with Logging {

  /** tricky point: make sure (library, uri) pair is unique.  */
  private def handleBookmarks(oldBookmarks: Seq[Keep])(implicit session: RWSession): Unit = {

    var urlToUriMap: Map[String, NormalizedURI] = Map()

    val deactivatedBms = oldBookmarks.map { oldBm =>

      // must get the new normalized uri from NormalizedURIRepo (cannot trust URLRepo due to its case sensitivity issue)
      val newUri = urlToUriMap.getOrElse(oldBm.url, {
        val newUri = normalizedURIInterner.getByUri(oldBm.url).getOrElse(normalizedURIInterner.internByUri(oldBm.url))
        urlToUriMap += (oldBm.url -> newUri)
        newUri
      })

      if (newUri.state == NormalizedURIStates.REDIRECTED) {
        // skipping due to double redirects. this should not happen.
        log.error(s"double uri redirect found: keepId=${oldBm.id.get} uriId=${newUri.id.get}")
        airbrake.notify(s"double uri redirect found: keepId=${oldBm.id.get} uriId=${newUri.id.get}")
        (None, None)
      } else {
        val libId = oldBm.libraryId.get // fail if there's no library
        val userId = oldBm.userId
        val newUriId = newUri.id.get
        val currentBookmarkOpt = if (oldBm.inDisjointLib)
          keepRepo.getPrimaryInDisjointByUriAndUser(newUriId, userId)
        else
          keepRepo.getPrimaryByUriAndLibrary(newUriId, libId)

        currentBookmarkOpt match {
          case None =>
            log.info(s"going to redirect bookmark's uri: (libId, newUriId) = (${libId.id}, ${newUriId.id}), db or cache returns None")
            keepUriUserCache.remove(KeepUriUserKey(oldBm.uriId, oldBm.userId)) // NOTE: we touch two different cache keys here and the following line
            keepRepo.save(helpers.improveKeepSafely(newUri, oldBm.withNormUriId(newUriId)))
            (Some(oldBm), None)
          case Some(currentPrimary) => {

            def save(duplicate: Keep, primary: Keep): (Option[Keep], Option[Keep]) = {
              val deadState = if (duplicate.isActive) KeepStates.DUPLICATE else duplicate.state
              val deadBm = keepRepo.save(
                duplicate.copy(uriId = newUriId, isPrimary = false, state = deadState)
              )
              val liveBm = keepRepo.save(helpers.improveKeepSafely(newUri, primary.copy(uriId = newUriId, isPrimary = true, state = KeepStates.ACTIVE)))
              keepUriUserCache.remove(KeepUriUserKey(deadBm.uriId, deadBm.userId))
              (Some(deadBm), Some(liveBm))
            }

            def orderByOldness(keep1: Keep, keep2: Keep): (Keep, Keep) = {
              if (keep1.createdAt.getMillis < keep2.createdAt.getMillis ||
                (keep1.createdAt.getMillis == keep2.createdAt.getMillis && keep1.id.get.id < keep2.id.get.id)) {
                (keep1, keep2)
              } else (keep2, keep1)
            }

            (oldBm.isActive, currentPrimary.isActive) match {
              case (true, true) =>
                // we are merging two active keeps, take the newer one
                val (older, newer) = orderByOldness(oldBm, currentPrimary)
                save(duplicate = older, primary = newer)

              case (true, false) =>
                // the current primary is INACTIVE. It will be marked as primary=false. the state remains INACTIVE
                save(duplicate = currentPrimary, primary = oldBm)

              case _ =>
                // oldBm is already inactive or duplicate, do nothing
                (None, None)
            }
          }
        }
      }
    }

    val collectionsToUpdate = deactivatedBms.map {
      case (Some(oldBm), None) => {
        keepToCollectionRepo.getCollectionsForKeep(oldBm.id.get).toSet
      }
      case (Some(oldBm), Some(newBm)) => {
        var collections = Set.empty[Id[Collection]]
        keepToCollectionRepo.getByKeep(oldBm.id.get, excludeState = None).foreach { ktc =>
          collections += ktc.collectionId
          keepToCollectionRepo.getOpt(newBm.id.get, ktc.collectionId) match {
            case Some(newKtc) =>
              if (ktc.state == KeepToCollectionStates.ACTIVE && newKtc.state == KeepToCollectionStates.INACTIVE) {
                keepToCollectionRepo.save(newKtc.copy(state = KeepToCollectionStates.ACTIVE))
              }
              keepToCollectionRepo.save(ktc.copy(state = KeepToCollectionStates.INACTIVE))
            case None =>
              keepToCollectionRepo.save(ktc.copy(keepId = newBm.id.get))
          }
        }
        collections
      }
      case _ => {
        Set.empty[Id[Collection]]
      }
    }.flatten

    collectionsToUpdate.foreach(collectionRepo.collectionChanged(_, inactivateIfEmpty = true))
  }

  /**
   * Any reference to the old uri should be redirected to the new one.
   * NOTE: We have 1-1 mapping from entities to url, the only exception (as of writing) is normalizedUri-url mapping, which is 1 to n.
   * A migration from uriA to uriB is (almost) equivalent to N url to uriB migrations, where the N urls are currently associated with uriA.
   */
  private def handleURIMigration(change: ChangedURI): Unit = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) {
      if (oldUriId == newUriId) {
        db.readWrite { implicit s =>
          changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
        }
      }
    } else {
      db.readWrite { implicit s =>
        normUriRepo.get(newUriId) match {
          case uri if uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED =>
            normUriRepo.save(uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None))
          case _ =>
        }
      }

      val urls = db.readWrite { implicit s =>
        urlRepo.getByNormUri(oldUriId)
      }
      db.readWriteSeq(urls) { (s, url) =>
        handleURLMigrationNoBookmarks(url, newUriId)(s)
      }

      // fix up redirections
      val previouslyRedirectedUris = db.readWrite { implicit s =>
        normUriRepo.getByRedirection(oldUriId)
      }
      db.readWriteSeq(previouslyRedirectedUris) { (s, uri) =>
        normUriRepo.save(uri.withRedirect(newUriId, currentDateTime))(s)
      }
      db.readWrite { implicit s =>
        val oldUri = normUriRepo.get(oldUriId)
        normUriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))
      }

      // retrieve bms by uri is more robust than by url (against cache bugs), in case bm and its url are pointing to different uris
      val bms = db.readWrite { implicit s =>
        keepRepo.getByUri(oldUriId, excludeState = None)
      }
      // process keeps for each user
      bms.groupBy(_.userId).foreach {
        case (_, keeps) =>
          db.readWrite { implicit s => handleBookmarks(keeps) }
      }

      // some additional sanity check right away!
      db.readWrite { implicit s =>
        checkIntegrity(newUriId, readOnly = false, hasKnownKeep = bms.size > 0)
      }

      db.readWrite { implicit s =>
        changedUriRepo.saveWithoutIncreSeqnum(change.withState(ChangedURIStates.APPLIED))
      }
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   */
  private def handleURLMigration(url: URL, newUriId: Id[NormalizedURI]): Unit = {
    db.readWrite { implicit s => handleURLMigrationNoBookmarks(url, newUriId) }

    val bms = db.readWrite { implicit s => keepRepo.getByUrlId(url.id.get) }

    // process keeps for each user
    bms.groupBy(_.userId).foreach {
      case (_, keeps) =>
        db.readWrite { implicit s => handleBookmarks(keeps) }
    }
  }

  private def handleURLMigrationNoBookmarks(url: URL, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    log.info(s"migrating url ${url.id} to new uri: $newUriId")

    val oldUriId = url.normalizedUriId
    urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, oldUriId, URLHistoryCause.MIGRATED)))
    val newUri = normUriRepo.get(newUriId)
    if (newUri.redirect.isDefined) normUriRepo.save(newUri.copy(redirect = None, redirectTime = None).withState(NormalizedURIStates.ACTIVE))
  }

  private def batchURIMigration(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pairs of uris to be merged")

    if (toMerge.size == 0) {
      log.debug("no active changed_uris were founded. Check if we have applied changed_uris generated during urlRenormalization")
      val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
      val applied = db.readOnlyReplica { implicit s => changedUriRepo.getChangesSince(lowSeq, batchSize, state = ChangedURIStates.APPLIED) }
      if (applied.size == batchSize) { // make sure a full batch of applied ones
        applied.sortBy(_.seq).lastOption.map { x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
        log.info(s"${applied.size} applied changed_uris are found!")
      }
      return applied.size
    }

    toMerge.map { change =>
      try {
        handleURIMigration(change)
      } catch {
        case e: Exception =>
          airbrake.notify(s"Exception in migrating uri ${change.oldUriId} to ${change.newUriId}. Going to delete them from cache", e)
          db.readWrite { implicit s => changedUriRepo.save(change.withState(ChangedURIStates.ACTIVE)) } // bump up seqNum. Will be retried.

          try {
            db.readOnlyMaster { implicit s => List(normUriRepo.get(change.oldUriId), normUriRepo.get(change.newUriId)) foreach { normUriRepo.deleteCache } }
          } catch {
            case e: Exception => airbrake.notify(s"error in getting uri ${change.oldUriId} or ${change.newUriId} from db by id.")
          }
      }
    }

    toMerge.sortBy(_.seq).lastOption.map { x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pairs of uris merged. zookeeper seqNum updated.")
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    log.info(s"batch uri migration: fetching tasks from seqNum $lowSeq")
    db.readOnlyReplica { implicit s => changedUriRepo.getChangesSince(lowSeq, fetchSize, state = ChangedURIStates.ACTIVE) }
  }

  private def batchURLMigration(batchSize: Int) = {
    val toMigrate = getOverDueURLMigrations(batchSize)
    log.info(s"${toMigrate.size} urls need renormalization")

    toMigrate.foreach { renormURL =>
      try {
        db.readWrite { implicit s =>
          val url = urlRepo.get(renormURL.urlId)
          handleURLMigration(url, renormURL.newUriId)
          renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.APPLIED))
        }
      } catch {
        case e: Exception =>
          airbrake.notify(e)
          db.readWrite { implicit s => renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.INACTIVE)) }
      }
    }

    toMigrate.sortBy(_.seq).lastOption.map { x => centralConfig.update(URLMigrationSeqNumKey, x.seq) }
    log.info(s"${toMigrate.size} urls renormalized.")
  }

  private def getOverDueURLMigrations(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URLMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    db.readOnlyReplica { implicit s => renormRepo.getChangesSince(lowSeq, fetchSize, state = RenormalizedURLStates.ACTIVE) }
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
            handleBookmarks(Seq(keep))(session)
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
    case URIMigration(oldUri, newUri) => db.readWrite { implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) } // process later
    case URLMigration(url, newUri) => handleURLMigration(url, newUri)
    case BatchURLMigration(batchSize) => batchURLMigration(batchSize)
    case FixDuplicateKeeps() => fixDuplicateKeeps()
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulerPlugin {
  def handleChangedUri(change: UriChangeMessage): Unit
  def batchURIMigration(batchSize: Int = -1): Future[Int]
  def batchURLMigration(batchSize: Int = -1): Unit
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
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 47 seconds, 43 seconds, actor.ref, BatchURIMigration(50), BatchURIMigration.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 55 seconds, 47 seconds, actor.ref, BatchURLMigration(100), BatchURLMigration.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 60 seconds, 53 seconds, actor.ref, FixDuplicateKeeps(), FixDuplicateKeeps.getClass.getSimpleName)
  }

  def handleChangedUri(change: UriChangeMessage) = {
    actor.ref ! change
  }

  def batchURIMigration(batchSize: Int) = actor.ref.ask(BatchURIMigration(batchSize))(1 minute).mapTo[Int]
  def batchURLMigration(batchSize: Int) = actor.ref ! BatchURLMigration(batchSize)

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
class UriIntegrityHelpers @Inject() (urlRepo: URLRepo, keepRepo: KeepRepo) extends Logging {
  def improveKeepSafely(uri: NormalizedURI, keep: Keep)(implicit session: RWSession): Keep = {
    require(keep.uriId == uri.id.get, "URI and Keep don't match.")
    val keepWithTitle = if (keep.title.isEmpty) keep.withTitle(uri.title) else keep
    if (HttpRedirect.isShortenedUrl(keepWithTitle.url)) {
      val urlObj = urlRepo.get(uri.url, uri.id.get).getOrElse(urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)))
      keepWithTitle.copy(url = urlObj.url, urlId = urlObj.id.get)
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
