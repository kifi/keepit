package com.keepit.shoebox.model

import com.keepit.common.db.{ ExternalId, Id }

sealed trait UserSessionRemoteModel

object Ids {
  type UserSessionId = Id[UserSessionRemoteModel]
  type UserSessionExternalId = ExternalId[UserSessionRemoteModel]
  def UserSessionExternalId(id: String) = ExternalId[UserSessionRemoteModel](id)
}
