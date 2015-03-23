package com.keepit.model

import com.keepit.common.db.Id
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent }
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RSession

import org.joda.time.DateTime

@Singleton
class TwitterSyncStateRepo @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[TwitterSyncState] {
  import db.Driver.simple._

  type RepoImpl = TwitterSyncStateTable

  class TwitterSyncStateTable(tag: Tag) extends RepoTable[TwitterSyncState](db, tag, "twitter_sync_state") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def twitterHandle = column[String]("twitter_handle", O.NotNull)
    def lastFetchedAt = column[DateTime]("last_fetched_at", O.Nullable)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def maxTweetIdSeen = column[Long]("max_tweet_id_seen", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, userId.?, twitterHandle, lastFetchedAt.?, libraryId, maxTweetIdSeen.?) <> ((TwitterSyncState.apply _).tupled, TwitterSyncState.unapply)
  }

  def table(tag: Tag) = new TwitterSyncStateTable(tag)
  initTable()

  override def deleteCache(model: TwitterSyncState)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: TwitterSyncState)(implicit session: RSession): Unit = {}

  def getSyncsToUpdate(refreshWindow: DateTime)(implicit session: RSession): Seq[TwitterSyncState] = {
    (for (row <- rows if (row.lastFetchedAt.isEmpty || row.lastFetchedAt <= refreshWindow) && row.state === TwitterSyncStateStates.ACTIVE) yield row).list
  }

}
