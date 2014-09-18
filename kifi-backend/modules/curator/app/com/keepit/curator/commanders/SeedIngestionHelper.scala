package com.keepit.curator.commanders

import com.keepit.common.db.Id
import com.keepit.model.User

import scala.concurrent.Future

trait GlobalSeedIngestionHelper {
  //triggers ingestions of up to maxItem RawSeedItems. Returns true if there might be more items to be ingested, false otherwise
  def apply(maxItems: Int): Future[Boolean]
}

trait PersonalSeedIngestionHelper {
  //triggers ingestions of RawSeedItems for the given user. Returns true if there might be more items to be ingested, false otherwise
  def apply(userId: Id[User], force: Boolean = false): Future[Boolean]
}

