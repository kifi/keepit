package com.keepit.shoebox.model

import com.keepit.common.db.{SurrogateExternalId, SurrogateExternalIdCompanion}

object Ids {
  case class UserSessionExternalId(id: String) extends SurrogateExternalId

  object UserSessionExternalId extends SurrogateExternalIdCompanion[Ids.UserSessionExternalId] {
    def create(id: String): Ids.UserSessionExternalId = Ids.UserSessionExternalId(id)
  }

}
