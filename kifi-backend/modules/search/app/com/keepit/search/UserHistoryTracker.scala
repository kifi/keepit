package com.keepit.search

import com.keepit.model.User
import com.keepit.common.db.Id
import com.google.inject.{Singleton, Inject}
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext

@Singleton
class UserHistoryTracker @Inject() (browsingHistoryTracker: BrowsingHistoryTracker, clickHistoryTracker: ClickHistoryTracker)  {

  def getUserHistory(userId: Id[User]): (Future[MultiHashFilter[BrowsedURI]], Future[MultiHashFilter[ClickedURI]]) =
    (SafeFuture(browsingHistoryTracker.getMultiHashFilter(userId)),
      SafeFuture(clickHistoryTracker.getMultiHashFilter(userId)))
}
