package com.keepit.common.social

import java.sql.Connection
import com.keepit.model._
import com.keepit.common.db.State
import com.keepit.common.db.ExternalId

case class BasicUser(externalId: ExternalId[User], firstName: String, lastName: String, avatar: String) // todo: avatar is a URL

object BasicUser {
  def apply(user: User)(implicit conn: Connection): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      avatar = "https://graph.facebook.com/" + SocialUserInfoCxRepo.getByUser(user.id.get).head.socialId.id + "/picture?type=square" // todo: fix?
    )
  }
}
