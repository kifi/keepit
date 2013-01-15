package com.keepit.common.social

import java.sql.Connection
import com.keepit.model.{User, SocialUserInfo}
import com.keepit.model.{Bookmark, BookmarkCxRepo}
import com.keepit.model.{EmailAddress, EmailAddressCxRepo}
import com.keepit.model.ExperimentType
import com.keepit.model.UserExperiment
import com.keepit.common.db.State

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo, bookmarksCount: Long, emails: Seq[EmailAddress], experiments: Seq[State[ExperimentType]])

object UserWithSocial {
  def toUserWithSocial(user: User)(implicit conn: Connection) = {
    val socialInfos = SocialUserInfo.getByUser(user.id.get)
    if (socialInfos.size != 1) throw new Exception("Expected to have exactly one social info for user %s, got %s. All social infos are: %s".
        format(user, socialInfos, SocialUserInfo.all))
    val bookmarksCount = BookmarkCxRepo.count(user)
    val emails = EmailAddressCxRepo.getByUser(user.id.get)
    val experiments = UserExperiment.getByUser(user.id.get).map(_.experimentType)
    UserWithSocial(user, socialInfos.head, bookmarksCount, emails, experiments)
  }
}
