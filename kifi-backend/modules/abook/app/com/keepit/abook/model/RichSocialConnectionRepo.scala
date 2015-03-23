package com.keepit.abook.model

import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.db.Id
import com.keepit.model.{ User, SocialUserInfo }
import com.keepit.common.time.Clock
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.social.{ SocialNetworks, SocialNetworkType }

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import scala.slick.jdbc.StaticQuery.interpolation
import com.keepit.common.core._
import com.keepit.common.mail.EmailAddress

@ImplementedBy(classOf[RichSocialConnectionRepoImpl])
trait RichSocialConnectionRepo extends Repo[RichSocialConnection] {
  def internRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection
  def recordInvitation(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress], invitationNumber: Int = 1)(implicit session: RWSession): Unit
  def cancelInvitation(userId: Id[User], friend: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Unit
  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], EmailAddress], friendUserId: Id[User])(implicit session: RWSession): Unit
  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Unit
  def getByUserAndSocialFriend(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RSession): Option[RichSocialConnection]
  def getByUserAndKifiFriend(userId: Id[User], kifiFriendId: Id[User])(implicit session: RSession): Seq[RichSocialConnection]
  def dedupedWTIForUser(user: Id[User], howMany: Int)(implicit session: RSession): Seq[Id[SocialUserInfo]]
  def removeRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: Id[SocialUserInfo])(implicit session: RWSession): Unit
  def countInvitationsSent(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RSession): Int
  def getRipestFruitsByCommonKifiFriendsCount(userId: Id[User], page: Int, pageSize: Int)(implicit session: RSession): Seq[RichSocialConnection]
  def getKifiFriends(userId: Id[User])(implicit session: RSession): Set[RichSocialConnection]
}

@Singleton
class RichSocialConnectionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[RichSocialConnection] with RichSocialConnectionRepo {

  import db.Driver.simple._

  type RepoImpl = RichSocialConnectionTable
  class RichSocialConnectionTable(tag: Tag) extends RepoTable[RichSocialConnection](db, tag, "rich_social_connection") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def userSocialId = column[Option[Id[SocialUserInfo]]]("user_social_id", O.Nullable)
    def connectionType = column[SocialNetworkType]("connection_type", O.NotNull)
    def friendSocialId = column[Option[Id[SocialUserInfo]]]("friend_social_id", O.Nullable)
    def friendEmailAddress = column[Option[EmailAddress]]("friend_email_address", O.Nullable)
    def friendUserId = column[Option[Id[User]]]("friend_user_id", O.Nullable)
    def friendName = column[Option[String]]("friend_name", O.Nullable)
    def commonKifiFriendsCount = column[Int]("common_kifi_friends_count", O.NotNull)
    def kifiFriendsCount = column[Int]("kifi_friends_count", O.NotNull)
    def invitationsSent = column[Int]("invitations_sent", O.NotNull)
    def invitedBy = column[Int]("invited_by", O.NotNull)
    def blocked = column[Boolean]("blocked", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, userId, userSocialId, connectionType, friendSocialId, friendEmailAddress, friendName, friendUserId, commonKifiFriendsCount, kifiFriendsCount, invitationsSent, invitedBy, blocked) <> ((RichSocialConnection.apply _).tupled, RichSocialConnection.unapply _)
  }

  def table(tag: Tag) = new RichSocialConnectionTable(tag)
  initTable()

  def deleteCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}
  def invalidateCache(model: RichSocialConnection)(implicit session: RSession): Unit = {}

  private val Email: SocialNetworkType = SocialNetworks.EMAIL
  private val FortyTwo: SocialNetworkType = SocialNetworks.FORTYTWO

  def getByUserAndSocialFriend(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RSession): Option[RichSocialConnection] = friendId match {
    case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row).firstOption
    case Right(friendEmailAddress) => (for { row <- rows if row.userId === userId && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row).firstOption
  }

  def getByUserAndKifiFriend(userId: Id[User], kifiFriendId: Id[User])(implicit session: RSession): Seq[RichSocialConnection] = {
    (for { row <- rows if row.userId === userId && row.friendUserId === kifiFriendId } yield row).list
  }

  def getKifiFriends(userId: Id[User])(implicit session: RSession): Set[RichSocialConnection] = {
    val q = for { row <- rows if row.userId === userId && row.friendUserId.isDefined && row.state === RichSocialConnectionStates.ACTIVE } yield row
    q.list.toSet
  }

  def internRichConnection(userId: Id[User], userSocialId: Option[Id[SocialUserInfo]], friend: Either[SocialUserInfo, EContact])(implicit session: RWSession): RichSocialConnection = {
    val (connectionType, friendName, friendUserId, friendId) = friend match {
      case Left(socialUserInfo) => (socialUserInfo.networkType, Some(socialUserInfo.fullName), socialUserInfo.userId, Left(socialUserInfo.id.get))
      case Right(eContact) => (Email, eContact.name, eContact.contactUserId, Right(eContact.email))
    }

    getByUserAndSocialFriend(userId, friendId) match {
      case Some(incompleteSocialConnection) if incompleteSocialConnection.friendUserId.isEmpty && friendUserId.isDefined =>
        save(incompleteSocialConnection.copy(friendUserId = friendUserId))
      case Some(inactiveConnection) if inactiveConnection.state == RichSocialConnectionStates.INACTIVE => {
        val (kifiFriendsCount, commonKifiFriendsCount) = incrementFriendsCounts(userId, friendId, friendUserId, connectionType)
        val invitedBy = countInviters(friendId)
        save(inactiveConnection.copy(
          commonKifiFriendsCount = commonKifiFriendsCount,
          kifiFriendsCount = kifiFriendsCount + 1,
          invitationsSent = 0,
          invitedBy = invitedBy,
          state = RichSocialConnectionStates.ACTIVE
        ))
      }
      case Some(richConnection) => richConnection
      case None => {
        val (kifiFriendsCount, commonKifiFriendsCount) = incrementFriendsCounts(userId, friendId, friendUserId, connectionType)
        val invitedBy = countInviters(friendId)

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
          invitationsSent = 0,
          invitedBy = invitedBy,
          blocked = false
        ))
      }
    }
  } tap { _ => sanityCheck(userId) }

  private def incrementFriendsCounts(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress], friendUserId: Option[Id[User]], connectionType: SocialNetworkType)(implicit session: RWSession): (Int, Int) = {
    if (connectionType == FortyTwo) {
      recordDirectedKifiConnection(userId, friendUserId.get)
      val userKifiFriends = getKifiFriends(userId).flatMap(_.friendUserId)
      val friendKifiFriends = getKifiFriends(friendUserId.get).flatMap(_.friendUserId)
      val kifiFriendsCount = userKifiFriends.size
      val commonKifiFriendsCount = (userKifiFriends intersect friendKifiFriends).size
      (kifiFriendsCount, commonKifiFriendsCount)
    } else {
      val kifiFriendsCount = incrementKifiFriendsCounts(friendId)
      val commonKifiFriendsCount = incrementCommonKifiFriendsCounts(userId, friendId)
      (kifiFriendsCount, commonKifiFriendsCount)
    }
  }

  def removeRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: Id[SocialUserInfo])(implicit session: RWSession): Unit = {
    getByUserAndSocialFriend(userId, Left(friend)) match {
      case Some(richConnection) if richConnection.state == RichSocialConnectionStates.ACTIVE => {
        if (richConnection.connectionType == SocialNetworks.FORTYTWO) {
          removeDirectedKifiConnection(richConnection.userId, richConnection.friendUserId.get)
        }
        decrementKifiFriendsCounts(friend)
        decrementCommonKifiFriendsCounts(userId, friend)
        save(richConnection.copy(state = RichSocialConnectionStates.INACTIVE))
      }
      case _ => //Nothing to remove if there is no such connection
    }
  }

  private def countInviters(friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RSession): Int = friendId match {
    case Left(friendSocialId) => (for { row <- rows if row.state === RichSocialConnectionStates.ACTIVE && row.friendSocialId === friendSocialId } yield row.invitedBy).firstOption getOrElse 0
    case Right(friendEmailAddress) => (for { row <- rows if row.state === RichSocialConnectionStates.ACTIVE && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row.invitedBy).firstOption getOrElse 0
  }

  private def incrementKifiFriendsCounts(friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Int = {
    val kifiFriendsCount = friendId match {
      case Left(friendSocialId) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE friend_social_id = $friendSocialId AND state='active'"
      case Right(friendEmailAddress) => sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count + 1 WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress AND state='active'"
    }
    kifiFriendsCount.first
  }
  private def decrementKifiFriendsCounts(friendId: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    sqlu"UPDATE rich_social_connection SET kifi_friends_count = kifi_friends_count - 1 WHERE friend_social_id = $friendId AND state='active'".first
  }

  private def incrementCommonKifiFriendsCounts(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Int = {
    val kifiFriendsIdSet: Set[Id[User]] = getKifiFriends(userId).flatMap(_.friendUserId)
    if (kifiFriendsIdSet.isEmpty) {
      0
    } else {
      val commonKifiFriendsCount = friendId match {
        case Left(friendSocialId) => sqlu"""
          UPDATE rich_social_connection
          SET common_kifi_friends_count = common_kifi_friends_count + 1
          WHERE friend_social_id = $friendSocialId AND state='active' AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
        """
        case Right(friendEmailAddress) => sqlu"""
          UPDATE rich_social_connection
          SET common_kifi_friends_count = common_kifi_friends_count + 1
          WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress AND state = 'active' AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
        """
      }
      commonKifiFriendsCount.first
    }
  }

  private def decrementCommonKifiFriendsCounts(userId: Id[User], friendId: Id[SocialUserInfo])(implicit session: RWSession): Int = {
    val kifiFriendsIdSet: Set[Id[User]] = getKifiFriends(userId).flatMap(_.friendUserId)
    if (kifiFriendsIdSet.isEmpty) {
      0
    } else {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE friend_social_id = $friendId AND state = 'active' AND user_id IN (#${kifiFriendsIdSet.mkString(",")})
      """.first
    }
  }

  private def recordDirectedKifiConnection(userId: Id[User], kifiFriend: Id[User])(implicit session: RWSession): Unit = {
    val socialFriendIdSet = sql"SELECT friend_social_id FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type != '#${FortyTwo}' AND friend_social_id IS NOT NULL AND state='active'".as[Long].list.toSet
    if (!socialFriendIdSet.isEmpty) {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE user_id = $userId AND state='active' AND friend_social_id IN (#${socialFriendIdSet.mkString(",")})
      """.execute
    }

    val emailFriendSet = sql"SELECT friend_email_address FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type = '#${Email}' AND state='active'".as[String].list.toSet.map { s: String => "\"" + s + "\"" }
    if (!emailFriendSet.isEmpty) {
      val q = sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count + 1
        WHERE user_id = $userId AND connection_type = '#${Email}' AND state='active' AND friend_email_address IN (#${emailFriendSet.mkString(",")})
      """
      try {
        q.execute
      } catch {
        case t: Throwable => {
          val stmnt: String = q.getStatement
          log.error(s"Error executing query < ${stmnt} > => ${t.toString}")
          throw t
        }
      }
    }

  }

  private def removeDirectedKifiConnection(userId: Id[User], kifiFriend: Id[User])(implicit session: RWSession): Unit = {
    val socialFriendIdSet = sql"SELECT friend_social_id FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type != '#${FortyTwo}' AND friend_social_id IS NOT NULL AND state='active'".as[Long].list.toSet
    if (!socialFriendIdSet.isEmpty) {
      sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE user_id = $userId AND state='active' AND friend_social_id IN (#${socialFriendIdSet.mkString(",")})
      """.execute
    }

    val emailFriendSet = sql"SELECT friend_email_address FROM rich_social_connection WHERE user_id = $kifiFriend AND connection_type = '#${Email}' AND state='active'".as[String].list.toSet.map { s: String => "\"" + s + "\"" }
    if (!emailFriendSet.isEmpty) {
      val q = sqlu"""
        UPDATE rich_social_connection
        SET common_kifi_friends_count = common_kifi_friends_count - 1
        WHERE user_id = $userId AND connection_type = '#${Email}' AND state='active' AND friend_email_address IN (#${emailFriendSet.mkString(",")})
      """
      q.execute
    }
  }

  def recordInvitation(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress], invitationNumber: Int)(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => {
        val updated = (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId && row.invitationsSent === invitationNumber - 1 } yield row.invitationsSent).update(invitationNumber)
        if (updated == 1 && invitationNumber == 1) {
          sqlu"UPDATE rich_social_connection SET invited_by = invited_by + 1 WHERE friend_social_id = $friendSocialId".execute
        }
      }
      case Right(friendEmailAddress) => {
        val updated = (for { row <- rows if row.userId === userId && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress && row.invitationsSent === invitationNumber - 1 } yield row.invitationsSent).update(invitationNumber)
        if (updated == 1 && invitationNumber == 1) {
          sqlu"UPDATE rich_social_connection SET invited_by = invited_by + 1 WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress".execute
        }
      }
    }
  }

  def cancelInvitation(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => {
        val updated = (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId && row.invitationsSent > 0 } yield row.invitationsSent).update(0)
        if (updated == 1) {
          sqlu"UPDATE rich_social_connection SET invited_by = invited_by - 1 WHERE friend_social_id = $friendSocialId".execute
        }
      }
      case Right(friendEmailAddress) => {
        val updated = (for { row <- rows if row.userId === userId && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress && row.invitationsSent > 0 } yield row.invitationsSent).update(0)
        if (updated == 1) {
          sqlu"UPDATE rich_social_connection SET invited_by = invited_by - 1 WHERE connection_type = '#${Email}' AND friend_email_address = $friendEmailAddress".execute
        }
      }
    }
  }

  def countInvitationsSent(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RSession): Int = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.invitationsSent)
      case Right(friendEmailAddress) => (for { row <- rows if row.userId === userId && row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row.invitationsSent)
    }
  }.firstOption getOrElse 0

  def recordFriendUserId(friendId: Either[Id[SocialUserInfo], EmailAddress], friendUserId: Id[User])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.friendSocialId === friendSocialId } yield row.friendUserId).update(Some(friendUserId))
      case Right(friendEmailAddress) => (for { row <- rows if row.connectionType === Email && row.friendEmailAddress === friendEmailAddress } yield row.friendUserId).update(Some(friendUserId))
    }
  }

  def block(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress])(implicit session: RWSession): Unit = {
    friendId match {
      case Left(friendSocialId) => (for { row <- rows if row.userId === userId && row.friendSocialId === friendSocialId } yield row.blocked).update(true)
      case Right(friendEmailAddress) => (for { row <- rows if row.connectionType === Email && row.userId === userId && row.friendEmailAddress === friendEmailAddress } yield row.blocked).update(true)
    }
  }

  def getRipestFruitsByCommonKifiFriendsCount(userId: Id[User], page: Int, pageSize: Int)(implicit session: RSession): Seq[RichSocialConnection] = {
    val kifiFriendsNameSet: Set[String] = getKifiFriends(userId).flatMap(_.friendName)
    val q = for {
      row <- rows if row.state === RichSocialConnectionStates.ACTIVE &&
        row.connectionType =!= FortyTwo &&
        row.userId === userId &&
        row.friendUserId.isEmpty &&
        row.blocked === false &&
        !row.friendName.inSet(kifiFriendsNameSet)
    } yield row
    q.sortBy(r => (r.commonKifiFriendsCount desc, r.kifiFriendsCount desc)).drop(page * pageSize).take(pageSize).list
  }

  def dedupedWTIForUser(user: Id[User], howMany: Int)(implicit session: RSession): Seq[Id[SocialUserInfo]] = {
    sql"""
      SELECT DISTINCT friend_social_id, kifi_friends_count FROM rich_social_connection WHERE user_id=$user AND friend_user_id is NULL AND friend_name NOT IN (
        SELECT friend_name FROM rich_social_connection WHERE user_id=$user AND friend_user_id is NOT NULL
      ) ORDER BY kifi_friends_count DESC LIMIT $howMany
    """.as[(Id[SocialUserInfo], Long)].list.map(_._1)
  }

  private def sanityCheck(userId: Id[User])(implicit session: RSession): Unit = {
    (for { row <- rows if row.userId === userId } yield row).iterator.foreach { connection =>
      if (connection.commonKifiFriendsCount > connection.kifiFriendsCount) {
        log.error(s"[WTI] Inconsistent bookkeeping on rich connection ${connection}")
      }
    }
  }
}
