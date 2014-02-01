package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.db.SequenceNumber

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

  private val sequence = db.getSequence("search_friend_sequence")

  override val table = new RepoTable[SearchFriend](db, "search_friend") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def friendId = column[Id[User]]("friend_id", O.NotNull)
    def seq = column[SequenceNumber]("seq", O.NotNull)
    def * = id.? ~ userId ~ friendId ~ state ~ createdAt ~ updatedAt ~ seq <> (SearchFriend, SearchFriend.unapply _)
  }

  override def save(model: SearchFriend)(implicit session: RWSession): SearchFriend = {
    val seqNum = sequence.incrementAndGet()
    super.save(model.copy(seq = seqNum))
  }

  override def invalidateCache(model: SearchFriend)(implicit session: RSession): Unit = {
    searchFriendsCache.remove(SearchFriendsKey(model.userId))
  }

  override def deleteCache(model: SearchFriend)(implicit session: RSession): Unit = {
    searchFriendsCache.remove(SearchFriendsKey(model.userId))
  }

  def getSearchFriends(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    searchFriendsCache.get(SearchFriendsKey(userId)) match {
      case Some(friends) => friends.map(Id[User]).toSet
      case _ =>
        val friends = getSearchFriendsFromDb(userId)
        searchFriendsCache.set(SearchFriendsKey(userId), friends.map(_.id).toArray)
        friends
    }
  }

  def excludeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val numUpdated = (for {
      f <- table if f.userId === userId && f.state === SearchFriendStates.INCLUDED && f.friendId.inSet(friendIds)
    } yield f.state ~ f.updatedAt).update(SearchFriendStates.EXCLUDED -> clock.now())
    val idsToInsert = friendIds -- (for (f <- table if f.userId === userId) yield f.friendId).list
    table.insertAll(idsToInsert.map { friendId => SearchFriend(userId = userId, friendId = friendId) }.toSeq: _*)
    val numChanged = idsToInsert.size + numUpdated
    if (numChanged > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId).map(_.id).toArray)
    }
    numChanged
  }

  def includeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val numUpdated = (for {
      f <- table if f.userId === userId && f.state === SearchFriendStates.EXCLUDED && f.friendId.inSet(friendIds)
    } yield f.state ~ f.updatedAt).update(SearchFriendStates.INCLUDED -> clock.now())
    if (numUpdated > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId).map(_.id).toArray)
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
