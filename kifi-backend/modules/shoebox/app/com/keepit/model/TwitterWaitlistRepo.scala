package com.keepit.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.social.twitter.TwitterHandle
import org.joda.time.DateTime

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[TwitterWaitlistRepoImpl])
trait TwitterWaitlistRepo extends Repo[TwitterWaitlistEntry] {
  def getByUserAndHandle(id: Id[User], handle: TwitterHandle)(implicit session: RSession): Option[TwitterWaitlistEntry]
  def getByUser(id: Id[User])(implicit session: RSession): Seq[TwitterWaitlistEntry]
  def countActiveEntriesBeforeDateTime(time: DateTime)(implicit session: RSession): Int
  def getAdminPage(implicit session: RSession): Seq[TwitterWaitlistEntry]
  def getPending(implicit session: RSession): Seq[TwitterWaitlistEntry]
}

@Singleton
class TwitterWaitlistRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[TwitterWaitlistEntry] with TwitterWaitlistRepo {

  import db.Driver.simple._

  type RepoImpl = TwitterWaitlistRepoTable

  implicit val twitterHandleColumnType = MappedColumnType.base[TwitterHandle, String](_.value, TwitterHandle(_))

  class TwitterWaitlistRepoTable(tag: Tag) extends RepoTable[TwitterWaitlistEntry](db, tag, "twitter_waitlist") {
    def userId = column[Id[User]]("user_id")
    def twitterHandle = column[Option[TwitterHandle]]("twitter_handle", O.Nullable)

    def * = (id.?, createdAt, updatedAt, userId, twitterHandle, state) <> ((TwitterWaitlistEntry.apply _).tupled, TwitterWaitlistEntry.unapply _)
  }

  def table(tag: Tag) = new TwitterWaitlistRepoTable(tag)

  initTable()

  def deleteCache(model: TwitterWaitlistEntry)(implicit session: RSession): Unit = {}
  def invalidateCache(model: TwitterWaitlistEntry)(implicit session: RSession): Unit = {}

  private val getByUserAndHandleCompiled = Compiled { (userId: Column[Id[User]], handle: Column[TwitterHandle]) =>
    for (r <- rows if r.userId === userId && r.twitterHandle === handle) yield r
  }
  def getByUserAndHandle(userId: Id[User], handle: TwitterHandle)(implicit session: RSession): Option[TwitterWaitlistEntry] = {
    getByUserAndHandleCompiled(userId, handle).firstOption
  }

  private val getByUserCompiled = Compiled { (userId: Column[Id[User]]) =>
    for (r <- rows if r.userId === userId) yield r
  }
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[TwitterWaitlistEntry] = {
    getByUserCompiled(userId).list
  }

  def countActiveEntriesBeforeDateTime(time: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select count(*) from twitter_waitlist tw where tw.state = 'active' and tw.created_at < $time"
    query.as[Int].first
  }

  def getAdminPage(implicit session: RSession): Seq[TwitterWaitlistEntry] = {
    (for (r <- rows if ((r.state === TwitterWaitlistEntryStates.ACTIVE || r.state === TwitterWaitlistEntryStates.ACCEPTED) && r.id > Id[TwitterWaitlistEntry](823))) yield r).list
  }

  def getPending(implicit session: RSession): Seq[TwitterWaitlistEntry] = {
    (for (r <- rows if r.state === TwitterWaitlistEntryStates.ACTIVE) yield r).list
  }

}
