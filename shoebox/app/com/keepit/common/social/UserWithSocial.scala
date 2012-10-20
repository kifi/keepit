package com.keepit.common.social

import java.sql.Connection
import com.keepit.model.{User, SocialUserInfo}

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo)

object UserWithSocial {
  def toUserWithSocial(user: User)(implicit conn: Connection) = {
    val socialInfo = SocialUserInfo.getByUser(user.id.get).head
    UserWithSocial(user, socialInfo)
  }
}