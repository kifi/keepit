package com.keepit.model

import com.keepit.common.db.Id
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.{ Repo, DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.social.twitter.TwitterHandle

import org.joda.time.DateTime

@ImplementedBy(classOf[TwitterSyncStateRepoImpl])
trait TwitterSyncStateRepo extends Repo[TwitterSyncState] {
  def getSyncsToUpdate(refreshWindow: DateTime)(implicit session: RSession): Seq[TwitterSyncState]
  def getByHandleAndLibraryId(handle: TwitterHandle, libId: Id[Library], target: SyncTarget)(implicit session: RSession): Option[TwitterSyncState]
  def getFirstHandleByLibraryId(libId: Id[Library])(implicit session: RSession): Option[TwitterHandle]
  def getByHandleAndUserIdUsed(handle: TwitterHandle, userIdUsed: Id[User], target: SyncTarget)(implicit session: RSession): Option[TwitterSyncState]
  def getAllByHandle(handle: TwitterHandle)(implicit session: RSession): Seq[TwitterSyncState]
  def getByUserIdUsed(userIdUsed: Id[User])(implicit session: RSession): Seq[TwitterSyncState]
  def getByUserIds(userIdUsed: Set[Id[User]])(implicit session: RSession): Seq[TwitterSyncState]

  // This needs to be rewritten. Does not work as expected.
  def getTwitterSyncsByFriendIds(twitterHandles: Set[TwitterHandle])(implicit session: RSession): Seq[TwitterSyncState]
}

@Singleton
class TwitterSyncStateRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    val twitterHandleCache: TwitterHandleCache) extends TwitterSyncStateRepo with DbRepo[TwitterSyncState] {
  import db.Driver.simple._

  implicit val twitterHandleColumnType = MappedColumnType.base[TwitterHandle, String](_.value, TwitterHandle(_))
  implicit val syncTargetColumnType = MappedColumnType.base[SyncTarget, String](_.value, SyncTarget.get(_))

  type RepoImpl = TwitterSyncStateTable

  class TwitterSyncStateTable(tag: Tag) extends RepoTable[TwitterSyncState](db, tag, "twitter_sync_state") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def twitterHandle = column[TwitterHandle]("twitter_handle", O.NotNull)
    def lastFetchedAt = column[Option[DateTime]]("last_fetched_at", O.Nullable)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def maxTweetIdSeen = column[Option[Long]]("max_tweet_id_seen", O.Nullable)
    def minTweetIdSeen = column[Option[Long]]("min_tweet_id_seen", O.Nullable)
    def syncTarget = column[Option[SyncTarget]]("sync_target", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, userId.?, twitterHandle, lastFetchedAt, libraryId, maxTweetIdSeen, minTweetIdSeen, syncTarget) <> ((TwitterSyncState.applyFromDbRow _).tupled, TwitterSyncState.unapplyToDbRow _)
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

  def getByHandleAndLibraryId(handle: TwitterHandle, libId: Id[Library], target: SyncTarget)(implicit session: RSession): Option[TwitterSyncState] = {
    (for { row <- rows if row.libraryId === libId && row.twitterHandle === handle && row.syncTarget === target } yield row).firstOption
  }

  def getFirstHandleByLibraryId(libId: Id[Library])(implicit session: RSession): Option[TwitterHandle] = {
    twitterHandleCache.getOrElseOpt(TwitterHandleLibraryIdKey(libId)) {
      val all = (for { row <- rows if row.libraryId === libId && row.state === TwitterSyncStateStates.ACTIVE } yield (row.id, row.syncTarget, row.twitterHandle)).list
      all.sortBy(_._1.id).find(_._2 == SyncTarget.Tweets).orElse(all.headOption).map(_._3)
    }
  }

  def getByHandleAndUserIdUsed(handle: TwitterHandle, userIdUsed: Id[User], target: SyncTarget)(implicit session: RSession): Option[TwitterSyncState] = {
    val all = (for { row <- rows if row.userId === userIdUsed && row.twitterHandle === handle } yield row).list
    val targeted = all.filter(r => r.target == target).sortBy(_.id.get.id)
    targeted.find(_.state == TwitterSyncStateStates.ACTIVE).orElse(targeted.headOption)
  }

  def getAllByHandle(handle: TwitterHandle)(implicit session: RSession): Seq[TwitterSyncState] = {
    (for { row <- rows if row.twitterHandle === handle } yield row).list
  }

  // This needs to be rewritten. Does not work as expected.
  def getTwitterSyncsByFriendIds(twitterHandles: Set[TwitterHandle])(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (r <- rows if r.twitterHandle.inSet(twitterHandles) && r.state === TwitterSyncStateStates.ACTIVE) yield r).list
  }

  def getByUserIdUsed(userIdUsed: Id[User])(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (r <- rows if r.userId === userIdUsed && r.state === TwitterSyncStateStates.ACTIVE) yield r).list
  }

  def getByUserIds(userIds: Set[Id[User]])(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (r <- rows if r.userId.inSet(userIds)) yield r).list
  }

}
