package com.keepit.commanders

import com.keepit.abook.model.{ RichSocialConnection, RichSocialConnectionRepo }
import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress

@Singleton
class WTICommander @Inject() (richSocialConnectionRepo: RichSocialConnectionRepo, db: Database) extends Logging {

  def ripestFruit(userId: Id[User], howMany: Int): Seq[Id[SocialUserInfo]] = db.readOnlyReplica { implicit session => richSocialConnectionRepo.dedupedWTIForUser(userId, howMany) }
  def countInvitationsSent(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]) = db.readOnlyReplica { implicit session => richSocialConnectionRepo.countInvitationsSent(userId, friend) }
  def blockRichConnection(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress]): Unit = db.readWrite { implicit session => richSocialConnectionRepo.block(userId, friend) }
  def getRipestFruitsByCommonKifiFriendsCount(userId: Id[User], page: Int, pageSize: Int): Seq[RichSocialConnection] = db.readOnlyReplica { implicit session => richSocialConnectionRepo.getRipestFruitsByCommonKifiFriendsCount(userId, page, pageSize) }
}
