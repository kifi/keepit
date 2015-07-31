package com.keepit.heimdal

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.model.{ PrimaryOrgForUserCache, Organization, User }

class PrimaryOrgProvider @Inject() (primaryOrgForUserCache: PrimaryOrgForUserCache) {
  def getPrimaryOrg(userId: Id[User]): Option[Id[Organization]] = ???
}

