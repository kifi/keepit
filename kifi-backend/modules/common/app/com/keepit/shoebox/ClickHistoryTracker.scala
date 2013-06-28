package com.keepit.shoebox

import com.keepit.common.db.Id
import com.keepit.model.{ClickHistory, NormalizedURI, User}
import com.keepit.search.MultiHashFilter
import net.codingwell.scalaguice.ScalaModule

trait ClickHistoryTracker {
  def add(userId: Id[User], uriId: Id[NormalizedURI]): ClickHistory
  def getMultiHashFilter(userId: Id[User]): MultiHashFilter[ClickHistory]
}
