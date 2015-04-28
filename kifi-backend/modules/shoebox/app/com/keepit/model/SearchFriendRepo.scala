package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.db.SequenceNumber

@ImplementedBy(classOf[SearchFriendRepoImpl])
trait SearchFriendRepo extends Repo[SearchFriend] with SeqNumberFunction[SearchFriend] {
  def getSearchFriends(userId: Id[User])(implicit session: RSession): Set[Id[User]]
  def getUnfriends(userId: Id[User])(implicit session: RSession): Set[Id[User]]
  def excludeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int
  def includeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int

  def excludeFriend(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Boolean = {
    excludeFriends(userId, Set(friendId)) > 0
  }
  def includeFriend(userId: Id[User], friendId: Id[User])(implicit session: RWSession): Boolean = {
    includeFriends(userId, Set(friendId)) > 0
  }
  def getSearchFriendsChanged(seq: SequenceNumber[SearchFriend], fetchSize: Int)(implicit session: RSession): Seq[SearchFriend]
}

@Singleton
class SearchFriendRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  userConnectionRepo: UserConnectionRepo,
  searchFriendsCache: SearchFriendsCache)
    extends DbRepo[SearchFriend] with SearchFriendRepo with SeqNumberDbFunction[SearchFriend] {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = SearchFriendTable
  class SearchFriendTable(tag: Tag) extends RepoTable[SearchFriend](db, tag, "search_friend") with SeqNumberColumn[SearchFriend] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def friendId = column[Id[User]]("friend_id", O.NotNull)
    def * = (id.?, userId, friendId, state, createdAt, updatedAt, seq) <> ((SearchFriend.apply _).tupled, SearchFriend.unapply _)
  }

  def table(tag: Tag) = new SearchFriendTable(tag)
  initTable()

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
    searchFriendsCache.getOrElse(SearchFriendsKey(userId)) { getSearchFriendsFromDb(userId) }
  }

  def getUnfriends(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    (for { f <- rows if f.userId === userId && f.state === SearchFriendStates.EXCLUDED } yield f.friendId).list.toSet
  }

  def excludeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val ids = (for {
      f <- rows if f.userId === userId && f.state === SearchFriendStates.INCLUDED && f.friendId.inSet(friendIds)
    } yield f.id).list

    ids.foreach { id =>
      (for (f <- rows if f.id === id) yield (f.state, f.updatedAt, f.seq)).update(SearchFriendStates.EXCLUDED, clock.now(), sequence.incrementAndGet())
    }

    val idsToInsert = friendIds -- (for (f <- rows if f.userId === userId) yield f.friendId).list
    rows.insertAll(idsToInsert.map { friendId => SearchFriend(userId = userId, friendId = friendId, seq = sequence.incrementAndGet()) }.toSeq: _*)
    val numChanged = idsToInsert.size + ids.size
    if (numChanged > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId))
    }
    numChanged
  }

  def includeFriends(userId: Id[User], friendIds: Set[Id[User]])(implicit session: RWSession): Int = {
    val ids = (for {
      f <- rows if f.userId === userId && f.state === SearchFriendStates.EXCLUDED && f.friendId.inSet(friendIds)
    } yield f.id).list

    ids.foreach { id =>
      (for (f <- rows if f.id === id) yield (f.state, f.updatedAt, f.seq)).update(SearchFriendStates.INCLUDED, clock.now(), sequence.incrementAndGet())
    }

    if (ids.size > 0) {
      searchFriendsCache.set(SearchFriendsKey(userId), getSearchFriendsFromDb(userId))
    }
    ids.size
  }

  private def getSearchFriendsFromDb(userId: Id[User])(implicit session: RSession): Set[Id[User]] = {
    val allConnections = userConnectionRepo.getConnectedUsers(userId)
    val excludedConnections = (for {
      f <- rows if f.userId === userId && f.state === SearchFriendStates.EXCLUDED
    } yield f.friendId).list.toSet
    allConnections -- excludedConnections
  }

  def getSearchFriendsChanged(seq: SequenceNumber[SearchFriend], fetchSize: Int)(implicit session: RSession): Seq[SearchFriend] = super.getBySequenceNumber(seq, fetchSize)
}
