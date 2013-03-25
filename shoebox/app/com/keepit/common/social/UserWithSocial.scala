package com.keepit.common.social

import java.sql.Connection
import play.api.Play.current
import com.keepit.common.db.slick.DBSession._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.common.db.State

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo, bookmarksCount: Int, emails: Seq[EmailAddress], experiments: Seq[State[ExperimentType]])

class UserWithSocialRepo {
  def toUserWithSocial(user: User)(implicit s: RSession) = {
    val socialInfos = inject[SocialUserInfoRepo].getByUser(user.id.get)
    if (socialInfos.size != 1) throw new Exception("Expected to have exactly one social info for user %s, got %s. All social infos are: %s".
        format(user, socialInfos, inject[SocialUserInfoRepo].all))
    val bookmarksCount = inject[BookmarkRepo].count(user.id.get)
    val emails = inject[EmailAddressRepo].getByUser(user.id.get)
    val experiments = inject[UserExperimentRepo].getUserExperiments(user.id.get)
    UserWithSocial(user, socialInfos.head, bookmarksCount, emails, experiments)
  }
}
