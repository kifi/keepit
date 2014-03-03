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
  def internRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection
  def recordKifiConnection(firstUserId: Id[User], secondUserId: Id[User])(implicit session: RWSession): Unit
  def recordInvitation(userId: Id[User], invitation: Id[Invitation], friend: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit
  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], String], friendUserId: Id[User])(implicit session: RWSession): Unit
  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit
  def getByUserAndFriend(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RSession): Option[RichSocialConnection]
  def getRipestFruit()(implicit session: RSession): Seq[Id[SocialUserInfo]]
  def removeRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: Id[SocialUserInfo])(implicit session: RWSession): Unit
  def removeKifiConnection(user1: Id[User], user2: Id[User])(implicit session: RWSession): Unit
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

  private val Email: SocialNetworkType = SocialNetworks.EMAIL

  def getByUserAndFriend(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RSession): Option[RichSocialConnection] = friendId match {
    case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row).firstOption()
    case Right(friendEmailAddress) => (for { row <- rows if row.userId === userId && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row).firstOption()
  }

  def internRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection = {
    val (connectionType, friendName, friendUserId, friendId) = friend match {
      case Left(socialUserInfo) => (socialUserInfo.networkType, Some(socialUserInfo.fullName), socialUserInfo.userId, Left(socialUserInfo.id.get))
      case Right(eContact) => (Email, eContact.name, eContact.contactUserId, Right(eContact.email))
    }

    getByUserAndFriend(userId, friendId) match {
      case Some(incompleteSocialConnection) if friendId.isLeft && incompleteSocialConnection.userSocialId.isEmpty && userSocialId.isDefined =>
        save(incompleteSocialConnection.copy(userSocialId = userSocialId))
      case Some(inactiveConnection) if inactiveConnection.state==RichSocialConnectionStates.INACTIVE => {
        val kifiFriendsCount = incrementKifiFriendsCounts(friendId)
        val commonKifiFriendsCount = incrementCommonKifiFriendsCounts(userId, friendId)
        val invitationCount = getInvitationCount(friendId)
        save(inactiveConnection.copy(
          commonKifiFriendsCount = commonKifiFriendsCount,
          kifiFriendsCount = kifiFriendsCount + 1,
          invitationCount = invitationCount,
          state = RichSocialConnectionStates.ACTIVE
        ))
      }
      case Some(richConnection) => richConnection
      case None => {
        val kifiFriendsCount = incrementKifiFriendsCounts(friendId)
        val commonKifiFriendsCount = incrementCommonKifiFriendsCounts(userId, friendId)
        val invitationCount = getInvitationCount(friendId)

        save(RichSocialConnection(
          userId = userId,
          userSocialId = userSocialId.filter(_ => friendId.isLeft),
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
    }
  }

  def removeRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: Id[SocialUserInfo])(implicit session: RWSession): Unit = {
    getByUserAndFriend(userId, Left(friend)) match {
      case Some(richConnection) if richConnection.state==RichSocialConnectionStates.ACTIVE => {
        decrementKifiFriendsCounts(friend)
        decrementCommonKifiFriendsCounts(userId, friend)
        save(richConnection.copy(state=RichSocialConnectionStates.INACTIVE))
      }
      case _ => //Nothing to remove if there is no such connection
    }
  }

  private def getInvitationCount(friendId: Either[Id[SocialUserInfo], String])(implicit session: RSession): Int = friendId match {
    case Left(friendSocialId) => (for { row <- rows if row.friendSocialId === friendSocialId } yield row.invitationCount).firstOption() getOrElse 0
    case Right(friendEmailAddress) => (for { row <- rows if row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row.invitationCount).firstOption() getOrElse 0
  }

  private def incrementKifiFriendsCounts(friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Int = {
    val kifiFriendsCount = friendId match {
      case Left(friendSocialId) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE friend_social_id = $friendSocialId"
      case Right(friendEmailAddress) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress"
    }
    kifiFriendsCount.first()
  }
  private def decrementKifiFriendsCounts(friendId: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count - 1 WHERE friend_social_id = $friendId".first()
  }

  private def incrementCommonKifiFriendsCounts(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Int = {
    val kifiFriendsIdSet = sql"SELECT friend_user_id FROM rich_social_connection WHERE user_id = $userId AND friend_user_id IS NOT NULL AND state='ACTIVE'".as[Long].list().toSet
    if (kifiFriendsIdSet.isEmpty) {
      0
    } else {
      val commonKifiFriendsCount = friendId match {
        case Left(friendSocialId) => sqlu"""
          UPDATE rich_social_connection
          SET common_kifi_friends_count = common_kifi_friends_count + 1
          WHERE friend_social_id = $friendSocialId AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
        """
        case Right(friendEmailAddress) => sqlu"""
          UPDATE rich_social_connection
          SET common_kifi_friends_count = common_kifi_friends_count + 1
          WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
        """
      }
      commonKifiFriendsCount.first()
    }
  }

  private def decrementCommonKifiFriendsCounts(userId: Id[User], friendId: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    val kifiFriendsIdSet = sql"SELECT friend_user_id FROM rich_social_connection WHERE user_id = $userId AND friend_user_id IS NOT NULL AND state='ACTIVE'".as[Long].list().toSet
    if (kifiFriendsIdSet.isEmpty) {
      0
    } else {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE friend_social_id = $friendId AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
      """.first()
    }
  }

  def recordKifiConnection(firstUserId: Id[User], secondUserId: Id[User])(implicit session: RWSession): Unit = {
    recordDirectedKifiConnection(firstUserId, secondUserId)
    recordDirectedKifiConnection(secondUserId, firstUserId)
  }

  def removeKifiConnection(user1: Id[User], user2: Id[User])(implicit session: RWSession): Unit = {
    removeDirectedKifiConnection(user1, user2)
    removeDirectedKifiConnection(user2, user1)
  }

  private def recordDirectedKifiConnection(userId: Id[User], kifiFriend: Id[User])(implicit session: RWSession): Unit = {
    val socialFriendIdSet = sql"SELECT friend_social_id FROM rich_social_connection WHERE user_id = $kifiFriend AND friend_social_id IS NOT NULL AND state='ACTIVE'".as[Long].list().toSet
    if (!socialFriendIdSet.isEmpty) {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE user_id = $userId AND friend_social_id IN (#${socialFriendIdSet.mkString(",")})
      """.execute()
    }


    val emailFriendSet = sql"SELECT friend_email_address FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type = '#${Email}' AND state='ACTIVE'".as[String].list().toSet.map{s : String => "'" + s + "'" }
    if (!emailFriendSet.isEmpty) {
      val q = sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE user_id = $userId AND connection_type = '#${Email}' AND friend_email_address IN (#${emailFriendSet.mkString(",")})
      """
      q.execute()
    }
  }

  private def removeDirectedKifiConnection(userId: Id[User], kifiFriend: Id[User])(implicit session: RWSession): Unit = {
    val socialFriendIdSet = sql"SELECT friend_social_id FROM rich_social_connection WHERE user_id = $kifiFriend AND friend_social_id IS NOT NULL AND state='ACTIVE'".as[Long].list().toSet
    if (!socialFriendIdSet.isEmpty) {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE user_id = $userId AND friend_social_id IN (#${socialFriendIdSet.mkString(",")})
      """.execute()
    }


    val emailFriendSet = sql"SELECT friend_email_address FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type = '#${Email}' AND state='ACTIVE'".as[String].list().toSet.map{s : String => "'" + s + "'" }
    if (!emailFriendSet.isEmpty) {
      val q = sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE user_id = $userId AND connection_type = '#${Email}' AND friend_email_address IN (#${emailFriendSet.mkString(",")})
      """
      q.execute()
    }
  }

  def recordInvitation(userId: Id[User], invitation: Id[Invitation], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => {
        (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.invitation).update(invitation)
        sqlu"UPDATE rich_social_connection SET invitation_count = invitation_count + 1 WHERE friend_social_id = $friendSocialId".execute()
      }
      case Right(friendEmailAddress) => {
        (for { row <- rows if row.userId === userId && row.friendEmailAddress === friendEmailAddress } yield row.invitation).update(invitation)
        sqlu"UPDATE rich_social_connection SET invitation_count = invitation_count + 1 WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress".execute()
      }
    }
  }

  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], String], friendUserId: Id[User])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.friendSocialId === friendSocialId } yield row.friendUserId).update(friendUserId)
      case Right(friendEmailAddress) => (for { row <- rows if row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row.friendUserId).update(friendUserId)
    }
  }

  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], String])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.blocked).update(true)
      case Right(friendEmailAddress) => (for { row <- rows if row.connectionType === Email && row.userId === userId && row.friendEmailAddress === friendEmailAddress } yield row.blocked).update(true)
    }
  }

  def getRipestFruit()(implicit session: RSession): Seq[Id[SocialUserInfo]]  = {
    sql"SELECT DISTINCT friend_social_id, kifi_friends_count FROM rich_social_connection WHERE friend_user_id is NULL ORDER BY kifi_friends_count DESC LIMIT 100".as[(Id[SocialUserInfo], Long)].list().map(_._1)
  }
}
