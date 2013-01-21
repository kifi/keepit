package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryHash
import java.io.File

object BrowsingHistoryTracker {
  def apply(filterSize: Int, numHashFuncs: Int, minHits: Int) = {
    new BrowsingHistoryTracker(filterSize, numHashFuncs, minHits)
  }
}

class BrowsingHistoryTracker(filterSize: Int, numHashFunc: Int, minHits: Int) {
  private[this] var filters = Map.empty[Id[User], MultiHashFilter]

  def add(userId: Id[User], uriId: Id[NormalizedURI]) = {
    getMultiHashFilter(userId).put(uriId.id)
  }

  def getMultiHashFilter(userId: Id[User]) = {
    filters.get(userId) match {
      case Some(filter) => filter
      case None =>
        val filter = MultiHashFilter(filterSize, numHashFunc, minHits)
        filters += (userId -> filter)
        filter
    }
  }
}