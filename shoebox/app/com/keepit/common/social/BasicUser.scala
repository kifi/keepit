package com.keepit.common.social

import java.sql.Connection
import com.keepit.model.{User, SocialUserInfo}
import com.keepit.model.Bookmark
import com.keepit.model.EmailAddress
import com.keepit.model.UserExperiment.ExperimentType
import com.keepit.common.db.State
import com.keepit.model.UserExperiment
import com.keepit.common.db.ExternalId

case class BasicUser(externalId: ExternalId[User], firstName: String, lastName: String, avatar: String) // todo: avatar is a URL

object BasicUser {
  def apply(user: User)(implicit conn: Connection): BasicUser = {
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      avatar = "https://graph.facebook.com/" + SocialUserInfo.getByUser(user.id.get).head.socialId.id + "/picture?type=square" // todo: fix?
    )
  }
}
