package com.keepit.shoebox

import com.keepit.common.db.Id
import com.keepit.model.{BrowsingHistory, NormalizedURI, User}
import com.keepit.search.MultiHashFilter

trait BrowsingHistoryTracker {
  def add(userId: Id[User], uriId: Id[NormalizedURI]): BrowsingHistory
  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[BrowsingHistory]
}
