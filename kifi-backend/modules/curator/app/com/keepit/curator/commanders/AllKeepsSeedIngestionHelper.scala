package com.keepit.curator.commanders

import com.keepit.model.{ SystemValueRepo, Name, Keep, NormalizedURI, KeepStates }
import com.keepit.curator.model.{ CuratorKeepInfoRepo, RawSeedItemRepo, RawSeedItem, CuratorKeepInfoStates, CuratorKeepInfo }
import com.keepit.common.db.{ SequenceNumber, State }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging

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
    shoebox: ShoeboxServiceClient) extends GlobalSeedIngestionHelper with Logging {

  private val SEQ_NUM_NAME: Name[SequenceNumber[Keep]] = Name("all_keeps_seq_num")

  private def updateRawSeedItem(rawSeedItem: RawSeedItem, uriId: Id[NormalizedURI], newDateCandidate: DateTime, countChange: Int, discoverable: Boolean)(implicit session: RWSession): Unit = {
    rawSeedsRepo.save(rawSeedItem.copy(
      uriId = uriId, //implicit renormalize :-)
      firstKept = if (newDateCandidate.isBefore(rawSeedItem.firstKept)) newDateCandidate else rawSeedItem.firstKept,
      lastKept = if (newDateCandidate.isAfter(rawSeedItem.lastKept)) newDateCandidate else rawSeedItem.lastKept,
      lastSeen = if (newDateCandidate.isAfter(rawSeedItem.lastSeen)) newDateCandidate else rawSeedItem.lastSeen,
      timesKept = rawSeedItem.timesKept + countChange,
      discoverable = discoverable
    ))
  }

  private def processKeep(keep: Keep)(implicit session: RWSession): Unit = {
    keepInfoRepo.getByKeepId(keep.id.get).map { keepInfo =>
      val rawSeedItemsByOldUriId = rawSeedsRepo.getByUriId(keepInfo.uriId)
      //deal correctly with the case where the item was renormalized by a previously ingested keep
      val rawSeedItems = if (rawSeedItemsByOldUriId.isEmpty) rawSeedsRepo.getByUriId(keep.uriId) else rawSeedItemsByOldUriId
      log.info(s"Got seed items: ${rawSeedItems} for keepInfo ${keepInfo} and keep ${keep}")
      require(rawSeedItems.length > 0, s"Missing RSI: keepId ${keepInfo.keepId}, uriId ${keepInfo.uriId}")
      val countChange = if (keep.state.value != keepInfo.state.value) {
        if (keepInfo.state == CuratorKeepInfoStates.ACTIVE) -1 else if (keep.state == KeepStates.ACTIVE) 1 else 0
      } else 0

      val discoverable = if (keepInfo.discoverable && keep.isPrivate) keepInfoRepo.checkDiscoverableByUriId(keep.uriId) else keepInfo.discoverable
      rawSeedItems.foreach { rawSeedItem =>
        updateRawSeedItem(rawSeedItem, keep.uriId, keep.createdAt, countChange, discoverable)
      }
      keepInfoRepo.save(keepInfo.copy(
        uriId = keep.uriId,
        userId = keep.userId,
        state = State[CuratorKeepInfo](keep.state.value),
        discoverable = !keep.isPrivate
      ))
    } getOrElse {
      keepInfoRepo.save(CuratorKeepInfo(
        uriId = keep.uriId,
        userId = keep.userId,
        keepId = keep.id.get,
        state = State[CuratorKeepInfo](keep.state.value),
        discoverable = !keep.isPrivate
      ))

      val rawSeedItems = rawSeedsRepo.getByUriId(keep.uriId)
      if (rawSeedItems.isEmpty) {
        rawSeedsRepo.save(RawSeedItem(
          uriId = keep.uriId,
          userId = None,
          firstKept = keep.createdAt,
          lastKept = keep.createdAt,
          lastSeen = keep.createdAt,
          priorScore = None,
          timesKept = if (keep.state == KeepStates.ACTIVE) 1 else 0,
          discoverable = !keep.isPrivate
        ))
      } else {
        rawSeedItems.foreach { rawSeedItem =>
          val discoverable = rawSeedItem.discoverable || !keep.isPrivate
          updateRawSeedItem(rawSeedItem, keep.uriId, keep.createdAt, if (keep.state == KeepStates.ACTIVE) 1 else 0, discoverable)
        }
      }

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
