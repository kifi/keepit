package com.keepit.shoebox.model

import com.keepit.common.db.{ SurrogateExternalIdCompanion, SurrogateExternalId, ExternalId, Id }

object Ids {
  //case class UserSessionId(id: Long) extends SurrogateId
  case class UserSessionExternalId(id: String) extends SurrogateExternalId

  object UserSessionExternalId extends SurrogateExternalIdCompanion[Ids.UserSessionExternalId] {
    def create(id: String): Ids.UserSessionExternalId = Ids.UserSessionExternalId(id)
  }

}
