package com.keepit.abook.model

import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.Id
import com.keepit.model.{User, SocialUserInfo, Invitation}
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.social.SocialNetworkType

import com.google.inject.{Inject, Singleton, ImplementedBy}


@ImplementedBy(classOf[RichSocialConnectionRepoImpl])
trait RichSocialConnectionRepo extends Repo[RichSocialConnection] {
  //TBD
}


@Singleton
class RichSocialConnectionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock)
  extends DbRepo[RichSocialConnection] with RichSocialConnectionRepo {

  import db.Driver.simple._

  type RepoImpl = RichSocialConnectionTable
  class RichSocialConnectionTable(tag: Tag) extends RepoTable[RichSocialConnection](db, tag, "rich_social_connection") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def userSocialId = column[Id[SocialUserInfo]]("user_social_id", O.Nullable)
    def connectionType = column[SocialNetworkType]("connection_type", O.NotNull)
    def friendSocialId = column[Id[SocialUserInfo]]("friend_social_id", O.Nullable)
    def friendEmailAddress = column[String]("friend_email_address", O.Nullable)
    def friendUserId = column[Id[User]]("friend_user_id", O.Nullable)
    def commonKifiFriendsCount = column[Int]("common_kifi_friends_count", O.NotNull)
    def kifiFriendsCount = column[Int]("kifi_friends_count", O.NotNull)
    def invitation = column[Id[Invitation]]("invitation", O.Nullable)
    def invitationCount =  column[Int]("invitation_count", O.NotNull)
    def blocked = column[Boolean]("blocked", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, userId, userSocialId.?, connectionType, friendSocialId.?, friendEmailAddress.?, friendUserId.?, commonKifiFriendsCount, kifiFriendsCount, invitation.?, invitationCount, blocked) <> ((RichSocialConnection.apply _).tupled, RichSocialConnection.unapply _)
  }

  def table(tag: Tag) = new RichSocialConnectionTable(tag)
  initTable()

  def deleteCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}

}
