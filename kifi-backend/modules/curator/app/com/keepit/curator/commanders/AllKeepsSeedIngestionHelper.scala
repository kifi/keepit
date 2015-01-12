package com.keepit.curator.commanders

import com.keepit.model.{ SystemValueRepo, Name, Keep, NormalizedURI, KeepStates }
import com.keepit.curator.model.{ CuratorKeepInfoRepo, RawSeedItemRepo, RawSeedItem, CuratorKeepInfoStates, CuratorKeepInfo }
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

import com.google.inject.{ Inject, Singleton }

import org.joda.time.DateTime

@Singleton
class AllKeepSeedIngestionHelper @Inject() (
    systemValueRepo: SystemValueRepo,
    keepInfoRepo: CuratorKeepInfoRepo,
    rawSeedsRepo: RawSeedItemRepo,
    db: Database,
    airbrake: AirbrakeNotifier,
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[Keep]] = Name("all_keeps_seq_num")

  private def dateMin(d0: DateTime, ds: DateTime*): DateTime = {
    var min = d0
    ds.foreach { d =>
      if (d.isBefore(min)) min = d
    }
    min
  }

  private def dateMax(d0: DateTime, ds: DateTime*): DateTime = {
    var max = d0
    ds.foreach { d =>
      if (d.isAfter(max)) max = d
    }
    max
  }

  private def updateRawSeedItem(seedItem: RawSeedItem, uriId: Id[NormalizedURI], newDateCandidate: DateTime, countChange: Int, discoverable: Boolean)(implicit session: RWSession): Unit = {
    if (seedItem.uriId != uriId) {
      //there was a renormalization of this item, thus we need to check if there should be a merge
      val existingItemOpt = rawSeedsRepo.getByUriIdAndUserId(uriId, seedItem.userId)
      existingItemOpt.map { existingItem =>
        //there is an item for this uriId (and possibly userId) already. Need to merge.
        val mergedFirstKept = dateMin(seedItem.firstKept, newDateCandidate, existingItem.firstKept)
        val mergedLastKept = dateMax(seedItem.firstKept, newDateCandidate, existingItem.firstKept)
        val mergedLastSeen = dateMax(seedItem.lastSeen, newDateCandidate, existingItem.lastSeen)
        val mergedTimesKept = seedItem.timesKept + countChange + existingItem.timesKept

        rawSeedsRepo.save(existingItem.copy(
          firstKept = mergedFirstKept,
          lastKept = mergedLastKept,
          lastSeen = mergedLastSeen,
          priorScore = if (seedItem.updatedAt.isAfter(existingItem.updatedAt)) seedItem.priorScore else existingItem.priorScore,
          timesKept = mergedTimesKept,
          discoverable = discoverable
        ))
        rawSeedsRepo.delete(seedItem)
      } getOrElse {
        //no exisitng item for this uriId (and possibly userId). Good to just move over.
        rawSeedsRepo.save(seedItem.copy(
          uriId = uriId,
          firstKept = if (newDateCandidate.isBefore(seedItem.firstKept)) newDateCandidate else seedItem.firstKept,
          lastKept = if (newDateCandidate.isAfter(seedItem.lastKept)) newDateCandidate else seedItem.lastKept,
          lastSeen = if (newDateCandidate.isAfter(seedItem.lastSeen)) newDateCandidate else seedItem.lastSeen,
          timesKept = seedItem.timesKept + countChange,
          discoverable = discoverable
        ))
      }
    } else {
      rawSeedsRepo.save(seedItem.copy(
        firstKept = if (newDateCandidate.isBefore(seedItem.firstKept)) newDateCandidate else seedItem.firstKept,
        lastKept = if (newDateCandidate.isAfter(seedItem.lastKept)) newDateCandidate else seedItem.lastKept,
        lastSeen = if (newDateCandidate.isAfter(seedItem.lastSeen)) newDateCandidate else seedItem.lastSeen,
        timesKept = seedItem.timesKept + countChange,
        discoverable = discoverable
      ))
    }
  }

  private def processNewKeep(keep: Keep)(implicit session: RWSession): Unit = {
    keepInfoRepo.save(CuratorKeepInfo(
      uriId = keep.uriId,
      userId = keep.userId,
      keepId = keep.id.get,
      libraryId = keep.libraryId,
      state = State[CuratorKeepInfo](keep.state.value),
      discoverable = !keep.isPrivate
    ))

    val rawSeedItems = rawSeedsRepo.getByUriId(keep.uriId)
    if (rawSeedItems.isEmpty) {
      rawSeedsRepo.save(RawSeedItem(
        uriId = keep.uriId,
        url = keep.url,
        userId = None,
        firstKept = keep.keptAt,
        lastKept = keep.keptAt,
        lastSeen = keep.keptAt,
        priorScore = None,
        timesKept = if (keep.state == KeepStates.ACTIVE) 1 else 0,
        discoverable = !keep.isPrivate && keep.state == KeepStates.ACTIVE
      ))
    } else {
      val discoverable = rawSeedItems(0).discoverable || (!keep.isPrivate && keep.state == KeepStates.ACTIVE)
      rawSeedItems.foreach { rawSeedItem =>
        updateRawSeedItem(rawSeedItem, keep.uriId, keep.keptAt, if (keep.state == KeepStates.ACTIVE) 1 else 0, discoverable)
      }
    }
  }

  private def processUpdatedKeep(keep: Keep, keepInfo: CuratorKeepInfo)(implicit session: RWSession): Unit = {
    val rawSeedItemsByOldUriId = rawSeedsRepo.getByUriId(keepInfo.uriId)
    //deal correctly with the case where the item was renormalized by a previously ingested keep
    val rawSeedItems = if (rawSeedItemsByOldUriId.isEmpty) rawSeedsRepo.getByUriId(keep.uriId) else rawSeedItemsByOldUriId
    log.info(s"Got seed items: ${rawSeedItems} for keepInfo ${keepInfo} and keep ${keep}")
    if (rawSeedItems.length > 0) {
      val countChange = if (keep.state.value != keepInfo.state.value) {
        if (keepInfo.state == CuratorKeepInfoStates.ACTIVE) -1 else if (keep.state == KeepStates.ACTIVE) 1 else 0
      } else 0

      keepInfoRepo.save(keepInfo.copy(
        uriId = keep.uriId,
        userId = keep.userId,
        libraryId = keep.libraryId,
        state = State[CuratorKeepInfo](keep.state.value),
        discoverable = !keep.isPrivate
      ))

      val discoverable = {
        if (!rawSeedItems(0).discoverable) {
          !keep.isPrivate && keep.state == KeepStates.ACTIVE
        } else {
          if (!keep.isPrivate && keep.state == KeepStates.ACTIVE) {
            true
          } else {
            if (!keep.isPrivate != keepInfo.discoverable || keep.state.value != keepInfo.state.value) {
              keepInfoRepo.checkDiscoverableByUriId(keep.uriId)
            } else {
              true
            }
          }
        }
      }

      rawSeedItems.foreach { rawSeedItem =>
        updateRawSeedItem(rawSeedItem, keep.uriId, keep.keptAt, countChange, discoverable)
      }
    } else {
      airbrake.notify(s"Missing RSI: keepId ${keepInfo.keepId}, oldUriId ${keepInfo.uriId}, newUriId ${keep.uriId}. Skipping this keep update.")
    }
  }

  private def processKeep(keep: Keep)(implicit session: RWSession): Unit = {
    keepInfoRepo.getByKeepId(keep.id.get).map { keepInfo =>
      processUpdatedKeep(keep, keepInfo)
    } getOrElse {
      processNewKeep(keep)
    }
  }

  def apply(maxItems: Int): Future[Boolean] = {
    val lastSeqNumFut: Future[SequenceNumber[Keep]] = db.readOnlyMasterAsync { implicit session =>
      systemValueRepo.getSequenceNumber(SEQ_NUM_NAME) getOrElse { SequenceNumber[Keep](0) }
    }

    lastSeqNumFut.flatMap { lastSeqNum =>
      shoebox.getBookmarksChanged(lastSeqNum, maxItems).flatMap { keeps =>
        db.readWriteAsync { implicit session =>
          keeps.foreach(processKeep)
          if (keeps.length > 0) systemValueRepo.setSequenceNumber(SEQ_NUM_NAME, keeps.map(_.seq).max)
          keeps.length >= maxItems
        }
      }
    }
  }

}
