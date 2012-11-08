package com.keepit.common.social

import java.sql.Connection
import com.keepit.model.{User, SocialUserInfo}
import com.keepit.model.Bookmark
import com.keepit.model.EmailAddress

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo, bookmarksCount: Long, emails: Seq[EmailAddress])

object UserWithSocial {
  def toUserWithSocial(user: User)(implicit conn: Connection) = {
    val socialInfos = SocialUserInfo.getByUser(user.id.get)
    if (socialInfos.size != 1) throw new Exception("Expected to have exactly one social info for user %s, got %s. All social infos are:".
        format(user, socialInfos, SocialUserInfo.all))
    val bookmarksCount = Bookmark.count(user)
    val emails = EmailAddress.getByUser(user.id.get)
    UserWithSocial(user, socialInfos.head, bookmarksCount, emails)
  }
}