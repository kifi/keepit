package com.keepit.curator.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.User
import org.joda.time.DateTime

@ImplementedBy(classOf[LastTopUriIngestionRepoImpl])
trait LastTopUriIngestionRepo extends DbRepo[LastTopUriIngestion] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[LastTopUriIngestion]
}

@Singleton
class LastTopUriIngestionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LastTopUriIngestion] with LastTopUriIngestionRepo {

  import db.Driver.simple._

  class LastTopUriIngestionTable(tag: Tag) extends RepoTable[LastTopUriIngestion](db, tag, "last_top_uri_ingestion_time") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def lastIngestionTime = column[DateTime]("last_ingestion_time", O.NotNull)

    def * = (id.?, createdAt, updatedAt, userId, lastIngestionTime) <> ((LastTopUriIngestion.apply _).tupled, LastTopUriIngestion.unapply _)
  }

  type RepoImpl = LastTopUriIngestionTable

  def table(tag: Tag) = new LastTopUriIngestionTable(tag)
  initTable()

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[LastTopUriIngestion] = {
    (for (row <- rows if row.userId === userId) yield row).firstOption
  }

  def deleteCache(model: LastTopUriIngestion)(implicit session: RSession): Unit = {}

  def invalidateCache(model: LastTopUriIngestion)(implicit session: RSession): Unit = {}
}

