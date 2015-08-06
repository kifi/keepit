package com.keepit.heimdal

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.model.{ PrimaryOrgForUserKey, PrimaryOrgForUserCache, Organization, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.cache._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

import scala.concurrent.Future

class PrimaryOrgProvider @Inject() (
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    shoebox: ShoeboxServiceClient) {
  def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]] = {
    primaryOrgForUserCache.getOrElseFutureOpt(PrimaryOrgForUserKey(userId)) {
      shoebox.getPrimaryOrg(userId)
    }
  }
}

