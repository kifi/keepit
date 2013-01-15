package com.keepit.common.social

import java.sql.Connection
import com.keepit.model._
import com.keepit.common.db.State

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo, bookmarksCount: Long, emails: Seq[EmailAddress], experiments: Seq[State[ExperimentType]])

object UserWithSocial {
  def toUserWithSocial(user: User)(implicit conn: Connection) = {
    val socialInfos = SocialUserInfoCxRepo.getByUser(user.id.get)
    if (socialInfos.size != 1) throw new Exception("Expected to have exactly one social info for user %s, got %s. All social infos are: %s".
        format(user, socialInfos, SocialUserInfoCxRepo.all))
    val bookmarksCount = BookmarkCxRepo.count(user)
    val emails = EmailAddressCxRepo.getByUser(user.id.get)
    val experiments = UserExperimentCxRepo.getByUser(user.id.get).map(_.experimentType)
    UserWithSocial(user, socialInfos.head, bookmarksCount, emails, experiments)
  }
}
