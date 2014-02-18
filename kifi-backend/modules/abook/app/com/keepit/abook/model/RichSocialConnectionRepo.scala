package com.keepit.abook.model

import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.Id
import com.keepit.model.{User, SocialUserInfo, Invitation}
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.RSession

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
    def connectionType = column[String]("connection_type", O.NotNull)
    def friendSocialId = column[Id[SocialUserInfo]]("friend_social_id", O.Nullable)
    def friendEmailAddress = column[String]("friend_email_address", O.Nullable)
    def friendUserId = column[Id[User]]("friend_user_id", O.Nullable)
    def localFriendCount = column[Int]("local_friend_count", O.NotNull)
    def globalFriendCount = column[Int]("global_friend_count", O.NotNull)
    def invitation = column[Id[Invitation]]("invitation", O.Nullable)
    def blocked = column[Boolean]("blocked", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, userId, userSocialId.?, connectionType, friendSocialId.?, friendEmailAddress.?, friendUserId.?, localFriendCount, globalFriendCount, invitation.?, blocked) <> ((RichSocialConnection.apply _).tupled, RichSocialConnection.unapply _)
  }

  def table(tag: Tag) = new RichSocialConnectionTable(tag)
  initTable()

  def deleteCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}

}
