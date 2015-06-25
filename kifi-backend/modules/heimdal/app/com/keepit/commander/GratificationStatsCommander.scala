package com.keepit.commander

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging

import com.keepit.model.{ GratificationData, Keep, KeepCountData, User, LibraryCountData }
import com.keepit.model.helprank.ReKeepRepo
import com.keepit.model.helprank.KeepDiscoveryRepo
import com.keepit.model.tracking.LibraryViewTrackingCommander
import com.keepit.common.time._

class GratificationStatsCommander @Inject() (
    db: Database,
    libViewCmdr: LibraryViewTrackingCommander,
    keepDiscoveryRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo) extends Logging {

  def getLibraryCountData(userId: Id[User]): LibraryCountData = {
    val LIBRARY_LIMIT = 7 // max libraries we want to show the user

    val since = currentDateTime.minusWeeks(1)
    val cnt = libViewCmdr.getTotalViews(userId, since)
    val map = libViewCmdr.getTopViewedLibrariesAndCounts(userId, since, LIBRARY_LIMIT)
    LibraryCountData(cnt, map)
  }

  def getKeepCountData(userId: Id[User]): KeepCountData = {
    val keepCounts = db.readOnlyReplica { implicit s => keepDiscoveryRepo.getUriDiscoveriesWithCountsByKeeper(userId, since = currentDateTime.minusWeeks(1)) }
    val keepTups = keepCounts.map {
      viewsByKeepTuple: (_, Id[Keep], _, Int) => (viewsByKeepTuple._2, viewsByKeepTuple._4)
    }
    val total = keepTups.map { _._2 }.sum
    KeepCountData(total, keepTups.toMap)
  }

  def getRekeepCountData(userId: Id[User]): KeepCountData = {
    val countsByKeep = db.readOnlyReplica { implicit s => rekeepRepo.getReKeepCountsByKeeper(userId = userId, since = currentDateTime.minusWeeks(1)) }
    val total = countsByKeep.values.sum
    KeepCountData(total, countsByKeep)
  }

  def getGratData(userId: Id[User]): GratificationData = {
    val libraryViews = getLibraryCountData(userId)
    val keepViews = getKeepCountData(userId)
    val rekeeps = getRekeepCountData(userId)
    GratificationData(userId, libraryViews, keepViews, rekeeps)
  }

}
