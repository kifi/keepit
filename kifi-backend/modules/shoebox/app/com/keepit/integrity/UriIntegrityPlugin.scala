package com.keepit.integrity

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import scala.concurrent.duration._
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.db.slick.DBSession.RWSession
import akka.pattern.{ask, pipe}
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

trait UriChangeMessage

case class URIMigration(oldUri: Id[NormalizedURI], newUri: Id[NormalizedURI]) extends UriChangeMessage
case class URLMigration(url: URL, newUri: Id[NormalizedURI]) extends UriChangeMessage
case class BatchURIMigration(batchSize: Int)
case class BatchURLMigration(batchSize: Int)
case class FixDuplicateKeeps()

class UriIntegrityActor @Inject()(
  db: Database,
  clock: Clock,
  uriRepo: NormalizedURIRepo,
  urlRepo: URLRepo,
  keepRepo: KeepRepo,
  changedUriRepo: ChangedURIRepo,
  keepToCollectionRepo: KeepToCollectionRepo,
  collectionRepo: CollectionRepo,
  renormRepo: RenormalizedURLRepo,
  centralConfig: CentralConfig,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {

  /** tricky point: make sure (user, uri) pair is unique.  */
  private def handleBookmarks(oldBookmarks: Seq[Keep])(implicit session: RWSession) = {

    var urlToUriMap: Map[String, NormalizedURI] = Map()

    val deactivatedBms = oldBookmarks.map{ oldBm =>

      // must get the new normalized uri from NormalizedURIRepo (cannot trust URLRepo due to its case sensitivity issue)
      val newUri = urlToUriMap.getOrElse(oldBm.url, {
        val newUri = uriRepo.getByUri(oldBm.url).getOrElse(uriRepo.internByUri(oldBm.url))
        urlToUriMap += (oldBm.url -> newUri)
        newUri
      })

      if (newUri.state == NormalizedURIStates.REDIRECTED) {
        // skipping due to double redirects. this should not happen.
        log.error(s"double uri redirect found: keepid=${oldBm.id.get} uriid=${newUri.id.get}")
        airbrake.notify(s"double uri redirect found: keepid=${oldBm.id.get} uriid=${newUri.id.get}")
        (None, None)
      } else {
        val userId = oldBm.userId
        val newUriId = newUri.id.get

        keepRepo.getPrimaryByUriAndUser(newUriId, userId) match {
          case None => {
            log.info(s"going to redirect bookmark's uri: (userId, newUriId) = (${userId.id}, ${newUriId.id}), db or cache returns None")
            keepRepo.deleteCache(oldBm)     // NOTE: we touch two different cache keys here and the following line
            keepRepo.save(oldBm.withNormUriId(newUriId))
            (Some(oldBm), None)
          }
          case Some(currentPrimary) => {

            def save(duplicate: Keep, primary: Keep, forcePrivate: Boolean): (Option[Keep], Option[Keep]) = {
              try {
                val deadState = if (duplicate.isActive) KeepStates.DUPLICATE else duplicate.state
                val deadBm = keepRepo.save(
                  duplicate.withNormUriId(newUriId).withPrimary(false).withState(deadState)
                )
                val liveBm = keepRepo.save(
                  (if (forcePrivate) primary.withPrivate(true) else primary).withNormUriId(newUriId).withPrimary(true).withState(KeepStates.ACTIVE)
                )
                keepRepo.deleteCache(deadBm)
                (Some(deadBm), Some(liveBm))
              } catch {
                case e: Throwable =>
                  e.printStackTrace()
                  throw e
              }
            }

            if (oldBm.isActive) {
              if (currentPrimary.isActive) {
                // we are merging two active keeps, take the newer one
                // if one of them is private, make the surviving keep private to true to be safe
                if (oldBm.createdAt.getMillis < currentPrimary.createdAt.getMillis ||
                    (oldBm.createdAt.getMillis == currentPrimary.createdAt.getMillis && oldBm.id.get.id < currentPrimary.id.get.id)) {
                  save(duplicate = oldBm, primary = currentPrimary, forcePrivate = oldBm.isPrivate || currentPrimary.isPrivate)
                } else {
                  save(duplicate = currentPrimary, primary = oldBm, forcePrivate = oldBm.isPrivate || currentPrimary.isPrivate)
                }
              } else {
                // the current primary is INACTIVE. It will be marked as primary=false. the state remains INACTIVE
                save(duplicate = currentPrimary, primary = oldBm, false)
              }
            } else {
              // oldBm is already inactive, do nothing
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

    collectionsToUpdate.foreach(collectionRepo.collectionChanged(_))
  }

  /**
   * Any reference to the old uri should be redirected to the new one.
   * NOTE: We have 1-1 mapping from entities to url, the only exception (as of writing) is normalizedUri-url mapping, which is 1 to n.
   * A migration from uriA to uriB is (almost) equivalent to N url to uriB migrations, where the N urls are currently associated with uriA.
   */
  private def handleURIMigration(change: ChangedURI)(implicit session: RWSession): Unit = {
    val (oldUriId, newUriId) = (change.oldUriId, change.newUriId)
    if (oldUriId == newUriId || change.state != ChangedURIStates.ACTIVE) {
      if (oldUriId == newUriId) changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.INACTIVE)))
    } else {
      val oldUri = uriRepo.get(oldUriId)
      uriRepo.get(newUriId) match {
        case uri if uri.state == NormalizedURIStates.INACTIVE || uri.state == NormalizedURIStates.REDIRECTED => uriRepo.save(uri.copy(state = NormalizedURIStates.ACTIVE, redirect = None, redirectTime = None))
        case _ =>
      }

      val urls = urlRepo.getByNormUri(oldUriId)
      urls.foreach{ url =>
        handleURLMigrationNoBookmarks(url, newUriId)
      }

      uriRepo.getByRedirection(oldUri.id.get).foreach{ uri =>
        uriRepo.save(uri.withRedirect(newUriId, currentDateTime))
      }
      uriRepo.save(oldUri.withRedirect(newUriId, currentDateTime))

      // retrieve bms by uri is more robust than by url (against cache bugs), in case bm and its url are pointing to different uris
      val bms = keepRepo.getByUri(oldUriId, excludeState = None)
      handleBookmarks(bms)

      changedUriRepo.saveWithoutIncreSeqnum((change.withState(ChangedURIStates.APPLIED)))
    }
  }

  /**
   * url now pointing to a new uri, any entity related to that url should update its uri reference.
   */
  private def handleURLMigration(url: URL, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
    handleURLMigrationNoBookmarks(url, newUriId)
    val bms = keepRepo.getByUrlId(url.id.get)
    handleBookmarks(bms)
  }

  private def handleURLMigrationNoBookmarks(url: URL, newUriId: Id[NormalizedURI])(implicit session: RWSession): Unit = {
      log.info(s"migrating url ${url.id} to new uri: ${newUriId}")

      val oldUriId = url.normalizedUriId
      urlRepo.save(url.withNormUriId(newUriId).withHistory(URLHistory(clock.now, oldUriId, URLHistoryCause.MIGRATED)))
      val newUri = uriRepo.get(newUriId)
      if (newUri.redirect.isDefined) uriRepo.save(newUri.copy(redirect = None, redirectTime = None).withState(NormalizedURIStates.ACTIVE))
  }

  private def batchURIMigration(batchSize: Int): Int = {
    val toMerge = getOverDueList(batchSize)
    log.info(s"batch merge uris: ${toMerge.size} pairs of uris to be merged")

    if (toMerge.size == 0){
      log.info("no active changed_uris were founded. Check if we have applied changed_uris generated during urlRenormalization")
      val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
      val applied = db.readOnly{ implicit s => changedUriRepo.getChangesSince(lowSeq, batchSize, state = ChangedURIStates.APPLIED)}
      if (applied.size == batchSize){   // make sure a full batch of applied ones
        applied.sortBy(_.seq).lastOption.map{ x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
        log.info(s"${applied.size} applied changed_uris are found!")
      }
      return applied.size
    }

    toMerge.map{ change =>
      try{
        db.readWrite{ implicit s => handleURIMigration(change) }
      } catch {
        case e: Exception => {
          airbrake.notify(s"Exception in migrating uri ${change.oldUriId} to ${change.newUriId}. Going to delete them from cache",e)
          db.readWrite{ implicit s => changedUriRepo.save((change.withState(ChangedURIStates.ACTIVE)))}   // bump up seqNum. Will be retried.

          try{
            db.readOnly{ implicit s => List(uriRepo.get(change.oldUriId), uriRepo.get(change.newUriId)) foreach {uriRepo.deleteCache} }
          } catch{
            case e: Exception => airbrake.notify(s"error in getting uri ${change.oldUriId} or ${change.newUriId} from db by id.")
          }
        }
      }
    }

    toMerge.sortBy(_.seq).lastOption.map{ x => centralConfig.update(URIMigrationSeqNumKey, x.seq) }
    log.info(s"batch merge uris completed in database: ${toMerge.size} pairs of uris merged. zookeeper seqNum updated.")
    toMerge.size
  }

  private def getOverDueList(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URIMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    log.info(s"batch uri migration: fetching tasks from seqNum ${lowSeq}")
    db.readOnly{ implicit s => changedUriRepo.getChangesSince(lowSeq, fetchSize, state = ChangedURIStates.ACTIVE)}
  }

  private def batchURLMigration(batchSize: Int) = {
    val toMigrate = getOverDueURLMigrations(batchSize)
    log.info(s"${toMigrate.size} urls need renormalization")

    toMigrate.foreach{ renormURL =>
      try{
        db.readWrite{ implicit s =>
          val url = urlRepo.get(renormURL.urlId)
          handleURLMigration(url, renormURL.newUriId)
          renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.APPLIED))
        }
      } catch {
        case e: Exception =>
          airbrake.notify(e)
          db.readWrite{ implicit s => renormRepo.saveWithoutIncreSeqnum(renormURL.withState(RenormalizedURLStates.INACTIVE)) }
      }
    }

    toMigrate.sortBy(_.seq).lastOption.map{ x => centralConfig.update(URLMigrationSeqNumKey, x.seq)}
    log.info(s"${toMigrate.size} urls renormalized.")
  }

  private def getOverDueURLMigrations(fetchSize: Int = -1) = {
    val lowSeq = centralConfig(URLMigrationSeqNumKey) getOrElse SequenceNumber.ZERO
    db.readOnly{ implicit s => renormRepo.getChangesSince(lowSeq, fetchSize, state = RenormalizedURLStates.ACTIVE)}
  }

  private def fixDuplicateKeeps(): Unit = {
    val seq = centralConfig(FixDuplicateKeepsSeqNumKey) getOrElse SequenceNumber.ZERO

    log.info(s"start deduping keeps: fetching tasks from seqNum ${seq}")
    try {
      var dedupedSuccessCount = 0
      val keeps = db.readOnly{ implicit s => keepRepo.getBookmarksChanged(seq, 1000) }
      if (keeps.nonEmpty) {
        db.readWriteBatch(keeps, 3){ (session, keep) =>
          uriRepo.getByUri(keep.url)(session) match {
            case Some(uri) =>
              if (keep.uriId != uri.id.get) {
                log.info(s"keep fixed [id=${keep.id.get}, oldUriId=${keep.uriId}, newUriId=${uri.id.get}]")

                handleBookmarks(Seq(keep))(session)
                dedupedSuccessCount += 1
              }
            case None =>
              log.info(s"keep fixed [id=${keep.id.get}, oldUriId=${keep.uriId}, newUriId=missing]")

              handleBookmarks(Seq(keep))(session)
              dedupedSuccessCount += 1
          }
        }
        centralConfig.update(FixDuplicateKeepsSeqNumKey, keeps.last.seq)
        log.info(s"keeps deduped [count=$dedupedSuccessCount/${keeps.size}]")
      }
      log.info(s"total $dedupedSuccessCount keeps deduplicated")
    } catch {
      case e: Throwable => log.error("deduping keeps failed", e)
    }
  }

  def receive = {
    case BatchURIMigration(batchSize) => Future.successful(batchURIMigration(batchSize)) pipeTo sender
    case URIMigration(oldUri, newUri) => db.readWrite{ implicit s => changedUriRepo.save(ChangedURI(oldUriId = oldUri, newUriId = newUri)) }   // process later
    case URLMigration(url, newUri) => db.readWrite{ implicit s => handleURLMigration(url, newUri)}
    case BatchURLMigration(batchSize) => batchURLMigration(batchSize)
    case FixDuplicateKeeps() => fixDuplicateKeeps()
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[UriIntegrityPluginImpl])
trait UriIntegrityPlugin extends SchedulerPlugin  {
  def handleChangedUri(change: UriChangeMessage): Unit
  def batchURIMigration(batchSize: Int = -1): Future[Int]
  def batchURLMigration(batchSize: Int = -1): Unit
}

@Singleton
class UriIntegrityPluginImpl @Inject() (
  actor: ActorInstance[UriIntegrityActor],
  val scheduling: SchedulingProperties
) extends UriIntegrityPlugin with Logging {
  override def enabled = true
  override def onStart() {
    log.info("starting UriIntegrityPluginImpl")
    scheduleTaskOnLeader(actor.system, 1 minutes, 45 seconds, actor.ref, BatchURIMigration(50))
    scheduleTaskOnLeader(actor.system, 1 minutes, 60 seconds, actor.ref, BatchURLMigration(100))
//    scheduleTaskOnLeader(actor.system, 1 minutes, 60 seconds, actor.ref, FixDuplicateKeeps())
  }
  override def onStop() {
    log.info("stopping UriIntegrityPluginImpl")
  }

  def handleChangedUri(change: UriChangeMessage) = {
    actor.ref ! change
  }

  def batchURIMigration(batchSize: Int) = actor.ref.ask(BatchURIMigration(batchSize))(1 minute).mapTo[Int]
  def batchURLMigration(batchSize: Int) = actor.ref ! BatchURLMigration(batchSize)
}
