package com.keepit.abook.model

import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.Id
import com.keepit.model.{EContact, User, SocialUserInfo, Invitation}
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.social.{SocialNetworks, SocialNetworkType}

import com.google.inject.{Inject, Singleton, ImplementedBy}
import scala.slick.jdbc.StaticQuery.interpolation


@ImplementedBy(classOf[RichSocialConnectionRepoImpl])
trait RichSocialConnectionRepo extends Repo[RichSocialConnection] {
  def createRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection
  def recordKifiConnection(firstUserId: Id[User], secondUserId: Id[User])(implicit session: RWSession): Unit
  def recordInvitation(userId: Id[User], invitation: Id[Invitation], friend: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit
  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], String], friendUserId: Id[User])(implicit session: RWSession): Unit
  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit
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
    def friendName = column[String]("friend_name", O.Nullable)
    def commonKifiFriendsCount = column[Int]("common_kifi_friends_count", O.NotNull)
    def kifiFriendsCount = column[Int]("kifi_friends_count", O.NotNull)
    def invitation = column[Id[Invitation]]("invitation", O.Nullable)
    def invitationCount =  column[Int]("invitation_count", O.NotNull)
    def blocked = column[Boolean]("blocked", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, userId, userSocialId.?, connectionType, friendSocialId.?, friendEmailAddress.?, friendName.?, friendUserId.?, commonKifiFriendsCount, kifiFriendsCount, invitation.?, invitationCount, blocked) <> ((RichSocialConnection.apply _).tupled, RichSocialConnection.unapply _)
  }

  def table(tag: Tag) = new RichSocialConnectionTable(tag)
  initTable()

  def deleteCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}

  def createRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection = {
    val (connectionType, friendName, friendUserId, friendId) = friend match {
      case Left(socialUserInfo) => (socialUserInfo.networkType, Some(socialUserInfo.fullName), socialUserInfo.userId, Left(socialUserInfo.id.get))
      case Right(eContact) => (SocialNetworks.EMAIL, eContact.name, eContact.contactUserId, Right(eContact.email))
    }

    val kifiFriendsCount = incrementKifiFriendsCounts(friendId)
    val commonKifiFriendsCount = incrementCommonKifiFriendsCounts(userId, friendId)
    val invitationCount = getInvitationCount(friendId)

    save(RichSocialConnection(
      userId = userId,
      userSocialId = userSocialId,
      connectionType = connectionType,
      friendSocialId = friendId.left.toOption,
      friendEmailAddress = friendId.right.toOption,
      friendName = friendName,
      friendUserId = friendUserId,
      commonKifiFriendsCount = commonKifiFriendsCount,
      kifiFriendsCount = kifiFriendsCount + 1,
      invitation = None,
      invitationCount = invitationCount,
      blocked = false
    ))
  }

  private def getInvitationCount(friendId: Either[Id[SocialUserInfo], String])(implicit session: RSession): Int = friendId match {
    case Left(friendSocialId) => (for { row <- rows if row.friendSocialId === friendSocialId } yield row.invitationCount).firstOption() getOrElse 0
    case Right(friendEmailAddress) => (for { row <- rows if row.friendEmailAddress === friendEmailAddress } yield row.invitationCount).firstOption() getOrElse 0
  }

  private def incrementKifiFriendsCounts(friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Int = {
    val kifiFriendsCount = friendId match {
      case Left(friendSocialId) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE friend_social_id = $friendSocialId"
      case Right(friendEmailAddress) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE friend_email_address = $friendEmailAddress"
    }
    kifiFriendsCount.first()
  }
  private def incrementCommonKifiFriendsCounts(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Int = {
    val commonKifiFriendsCount = friendId match {
      case Left(friendSocialId) => sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE friend_social_id = $friendSocialId AND user_id IN (
          SELECT friend_user_id
          FROM rich_social_connection
          WHERE user_id = $userId AND friend_user_id IS NOT NULL
        )
      """
      case Right(friendEmailAddress) => sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE friend_email_address = $friendEmailAddress AND user_id IN (
          SELECT friend_user_id
          FROM rich_social_connection
          WHERE user_id = $userId AND friend_user_id IS NOT NULL
        )
      """
    }
    commonKifiFriendsCount.first()
  }

  def recordKifiConnection(firstUserId: Id[User], secondUserId: Id[User])(implicit session: RWSession): Unit = {
    recordDirectedKifiConnection(firstUserId, secondUserId)
    recordDirectedKifiConnection(secondUserId, firstUserId)
  }

  private def recordDirectedKifiConnection(userId: Id[User], kifiFriend: Id[User])(implicit session: RWSession): Unit = {
    sqlu"""
      UPDATE rich_social_connection
      SET common_kifi_friends_count = common_kifi_friends_count + 1
      WHERE user_id = $userId AND friend_email_address, friend_social_id IN (
        SELECT friend_email_address, friend_social_id
        FROM rich_social_connection
        WHERE user_id = $kifiFriend
      )
    """.execute()
  }

  def recordInvitation(userId: Id[User], invitation: Id[Invitation], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => {
        (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.invitation).update(invitation)
        sqlu"UPDATE rich_social_connection SET invitation_count = invitation_count + 1 WHERE friend_social_id = $friendSocialId".execute()
      }
      case Right(friendEmailAddress) => {
        (for { row <- rows if row.userId === userId && row.friendEmailAddress === friendEmailAddress } yield row.invitation).update(invitation)
        sqlu"UPDATE rich_social_connection SET invitation_count = invitation_count + 1 WHERE friend_email_address = $friendEmailAddress".execute()
      }
    }
  }

  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], String], friendUserId: Id[User])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.friendSocialId === friendSocialId } yield row.friendUserId).update(friendUserId)
      case Right(friendEmailAddress) => (for { row <- rows if row.friendEmailAddress === friendEmailAddress } yield row.friendUserId).update(friendUserId)
    }
  }

  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.blocked).update(true)
      case Right(friendEmailAddress) => (for { row <- rows if row.userId === userId && row.friendEmailAddress === friendEmailAddress } yield row.blocked).update(true)
    }
  }
}
