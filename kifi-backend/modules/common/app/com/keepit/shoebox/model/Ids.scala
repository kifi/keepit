package com.keepit.shoebox.model

import com.keepit.common.db.{ SurrogateExternalId, SurrogateExternalIdCompanion }

package ids {
  case class UserSessionExternalId(id: String) extends SurrogateExternalId
  object UserSessionExternalId extends SurrogateExternalIdCompanion[UserSessionExternalId]
}
