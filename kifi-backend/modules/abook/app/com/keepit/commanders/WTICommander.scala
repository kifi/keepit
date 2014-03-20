package com.keepit.commanders

import com.keepit.abook.model.RichSocialConnectionRepo
import com.keepit.model.{SocialUserInfo, User}
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id

import com.google.inject.{Inject, Singleton}

@Singleton
class WTICommander @Inject() (richSocialConnectionRepo: RichSocialConnectionRepo, db: Database) {

  def ripestFruit(userId: Id[User], howMany: Int): Seq[Id[SocialUserInfo]] = db.readOnly { implicit session => richSocialConnectionRepo.dedupedWTIForUser(userId, howMany) }
  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], String]) = db.readOnly { implicit session => richSocialConnectionRepo.countInvitationsSent(userId, friend) }
}
