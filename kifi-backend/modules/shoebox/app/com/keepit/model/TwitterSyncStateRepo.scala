package com.keepit.model

import com.keepit.common.db.Id
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

import org.joda.time.DateTime

@ImplementedBy(classOf[TwitterSyncStateRepoImpl])
trait TwitterSyncStateRepo extends Repo[TwitterSyncState] {
  def getSyncsToUpdate(refreshWindow: DateTime)(implicit session: RSession): Seq[TwitterSyncState]
  def getByHandleAndLibraryId(handle: String, libId: Id[Library])(implicit session: RSession): Option[TwitterSyncState]
  def getFirstHandleByLibraryId(libId: Id[Library])(implicit session: RSession): Option[String]
  def getByHandleAndUserIdUsed(handle: String, userIdUsed: Id[User])(implicit session: RSession): Option[TwitterSyncState]

  // This needs to be rewritten. Does not work as expected.
  def getTwitterSyncsByFriendIds(twitterHandles: Set[String])(implicit session: RSession): Seq[TwitterSyncState]
}

@Singleton
class TwitterSyncStateRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val twitterHandleCache: TwitterHandleCache) extends TwitterSyncStateRepo with DbRepo[TwitterSyncState] {
  import db.Driver.simple._

  type RepoImpl = TwitterSyncStateTable

  class TwitterSyncStateTable(tag: Tag) extends RepoTable[TwitterSyncState](db, tag, "twitter_sync_state") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def twitterHandle = column[String]("twitter_handle", O.NotNull)
    def lastFetchedAt = column[Option[DateTime]]("last_fetched_at", O.Nullable)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def maxTweetIdSeen = column[Option[Long]]("max_tweet_id_seen", O.Nullable)
    def minTweetIdSeen = column[Option[Long]]("min_tweet_id_seen", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, userId.?, twitterHandle, lastFetchedAt, libraryId, maxTweetIdSeen, minTweetIdSeen) <> ((TwitterSyncState.apply _).tupled, TwitterSyncState.unapply)
  }

  def table(tag: Tag) = new TwitterSyncStateTable(tag)
  initTable()

  def deleteCache(model: TwitterSyncState)(implicit session: RSession): Unit = {
    twitterHandleCache.remove(TwitterHandleLibraryIdKey(model.libraryId))
  }
  def invalidateCache(model: TwitterSyncState)(implicit session: RSession): Unit = {
    twitterHandleCache.set(TwitterHandleLibraryIdKey(model.libraryId), model.twitterHandle)
  }

  def getSyncsToUpdate(refreshWindow: DateTime)(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (row <- rows if (row.lastFetchedAt.isEmpty || row.lastFetchedAt <= refreshWindow) && row.state === TwitterSyncStateStates.ACTIVE) yield row)
      .sortBy(_.lastFetchedAt.asc).list
  }

  def getByHandleAndLibraryId(handle: String, libId: Id[Library])(implicit session: RSession): Option[TwitterSyncState] = {
    (for { row <- rows if row.libraryId === libId && row.twitterHandle === handle } yield row).firstOption
  }

  def getFirstHandleByLibraryId(libId: Id[Library])(implicit session: RSession): Option[String] = {
    twitterHandleCache.getOrElseOpt(TwitterHandleLibraryIdKey(libId)) {
      (for { row <- rows if row.libraryId === libId && row.state === TwitterSyncStateStates.ACTIVE } yield row.twitterHandle).firstOption
    }
  }

  def getByHandleAndUserIdUsed(handle: String, userIdUsed: Id[User])(implicit session: RSession): Option[TwitterSyncState] = {
    (for { row <- rows if row.userId === userIdUsed && row.twitterHandle === handle } yield row).firstOption
  }

  // This needs to be rewritten. Does not work as expected.
  def getTwitterSyncsByFriendIds(twitterHandles: Set[String])(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (r <- rows if r.twitterHandle.inSet(twitterHandles) && r.state === TwitterSyncStateStates.ACTIVE) yield r).list
  }

}
