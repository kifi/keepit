package com.keepit.common.social

import java.sql.Connection
import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.db.State

case class UserWithSocial(user: User, socialUserInfo: SocialUserInfo, bookmarksCount: Int, experiments: Set[State[ExperimentType]])

class UserWithSocialRepo @Inject() (
    socialUserInfoRepo: SocialUserInfoRepo,
    bookmarkRepo: BookmarkRepo,
    userExperimentRepo: UserExperimentRepo) {

  def toUserWithSocial(user: User)(implicit s: RSession) = {
    val socialInfos = socialUserInfoRepo.getByUser(user.id.get)
    if (socialInfos.size != 1) throw new Exception(s"Expected to have exactly one social info for user $user, got $socialInfos")
    val bookmarksCount = bookmarkRepo.count(user.id.get)
    val experiments = userExperimentRepo.getUserExperiments(user.id.get)
    UserWithSocial(user, socialInfos.head, bookmarksCount, experiments)
  }

}
