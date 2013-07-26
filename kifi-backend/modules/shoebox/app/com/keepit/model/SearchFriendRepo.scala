package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time._

@ImplementedBy(classOf[SearchFriendRepoImpl])
trait SearchFriendRepo extends Repo[SearchFriend] {
  def getSearchFriends(userId: Id[User])(implicit session: RSession): Set[Id[User]]
  def excludeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int
  def includeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int

  def excludeFriend(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Boolean = {
    excludeFriends(userId, Set(friendId)) > 0
  }
  def includeFriend(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Boolean = {
    includeFriends(userId, Set(friendId)) > 0
  }
}

@Singleton
class SearchFriendRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    userConnectionRepo: UserConnectionRepo,
    searchFriendsCache: SearchFriendsCache)
    extends DbRepo[SearchFriend] with SearchFriendRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override val table = new RepoTable[SearchFriend](db, "filtered_friend") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def friendId = column[Id[User]]("friend_id", O.NotNull)
    def * = id.? ~ userId ~ friendId ~ state ~ createdAt ~ updatedAt <> (SearchFriend, SearchFriend.unapply _)
  }

  override def invalidateCache(model: SearchFriend)(implicit session: RSession) = {
    searchFriendsCache.remove(SearchFriendsKey(model.userId))
    model
  }

  def getSearchFriends(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    searchFriendsCache.getOrElse(SearchFriendsKey(userId))(getSearchFriendsFromDb(userId))
  }

  def excludeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val numUpdated = (for {
      f <- table if f.userId === userId && f.state === SearchFriendStates.INCLUDED && f.friendId.inSet(friendIds)
    } yield f.state ~ f.updatedAt).update(SearchFriendStates.EXCLUDED -> clock.now())
    val idsToInsert = friendIds -- (for (f <- table if f.userId === userId) yield f.friendId).list
    table.insertAll(idsToInsert.map { friendId => SearchFriend(userId = userId, friendId = friendId) }.toSeq: _*)
    val numChanged = idsToInsert.size + numUpdated
    if (numChanged > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId))
    }
    numChanged
  }

  def includeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val numUpdated = (for {
      f <- table if f.userId === userId && f.state === SearchFriendStates.EXCLUDED && f.friendId.inSet(friendIds)
    } yield f.state ~ f.updatedAt).update(SearchFriendStates.INCLUDED -> clock.now())
    if (numUpdated > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId))
    }
    numUpdated
  }

  private def getSearchFriendsFromDb(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    val allConnections = userConnectionRepo.getConnectedUsers(userId)
    val excludedConnections = (for {
      f <- table if f.userId === userId && f.state === SearchFriendStates.EXCLUDED
    } yield f.friendId).list.toSet
    allConnections -- excludedConnections
  }
}
