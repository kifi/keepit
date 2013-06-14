package com.keepit.shoebox

import com.keepit.common.db.Id
import com.keepit.model.{ClickHistoryRepo, User, NormalizedURI}
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.google.inject.ImplementedBy
import com.google.inject.Singleton
import com.keepit.model.ClickHistoryRepoImpl
import com.keepit.search.MultiHashFilter
import com.keepit.model._

class ClickHistoryTracker (tableSize: Int, numHashFuncs: Int, minHits: Int, repo: ClickHistoryRepo, db: Database) extends Logging {

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    val filter = getMultiHashFilter(userId)
    filter.put(uriId.id)

    db.readWrite { implicit session =>
      repo.save(repo.getByUserId(userId) match {
        case Some(bh) =>
          bh.withFilter(filter.getFilter)
        case None =>
          ClickHistory(userId = userId, tableSize = tableSize, filter = filter.getFilter, numHashFuncs = numHashFuncs, minHits = minHits)
      })
    }
  }

  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[ClickHistory] = {
    db.readOnly { implicit session =>
      repo.getByUserId(userId) match {
        case Some(clickHistory) =>
          new MultiHashFilter[ClickHistory](clickHistory.tableSize, clickHistory.filter, clickHistory.numHashFuncs, clickHistory.minHits)
        case None =>
          val filter = MultiHashFilter[ClickHistory](tableSize, numHashFuncs, minHits)
          filter
      }
    }
  }
}
