package com.keepit.model

package id {

  import com.keepit.common.db.{ ExternalId, Id }

  sealed trait UserSessionRemoteModel

  object Types {
    type UserSessionId = Id[UserSessionRemoteModel]
    type UserSessionExternalId = ExternalId[UserSessionRemoteModel]
    def UserSessionExternalId(id: String) = ExternalId[UserSessionRemoteModel](id)
  }

}