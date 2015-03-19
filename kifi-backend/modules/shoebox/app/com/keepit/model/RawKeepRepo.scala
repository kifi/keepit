package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import play.api.libs.json.JsValue
import scala.util.Try
import scala.slick.jdbc.StaticQuery
import org.joda.time.DateTime

@ImplementedBy(classOf[RawKeepRepoImpl])
trait RawKeepRepo extends Repo[RawKeep] {
  def getUnprocessedAndMarkAsImporting(batchSize: Int)(implicit session: RSession): Seq[RawKeep]
  def getOldUnprocessed(batchSize: Int, before: DateTime)(implicit session: RSession): Seq[RawKeep]
  def setState(rawKeepId: Id[RawKeep], state: State[RawKeep])(implicit session: RWSession): Boolean
  def insertAll(rawKeeps: Seq[RawKeep])(implicit session: RWSession): Try[Int]
  def insertOne(rawKeep: RawKeep)(implicit session: RWSession): Try[Boolean]
}

@Singleton
class RawKeepRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[RawKeep] with RawKeepRepo {
  import db.Driver.simple._

  type RepoImpl = RawKeepTable
  class RawKeepTable(tag: Tag) extends RepoTable[RawKeep](db, tag, "raw_keep") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def title = column[String]("title", O.Nullable)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def importId = column[String]("import_id", O.Nullable)
    def source = column[KeepSource]("source", O.NotNull)
    def kifiInstallationId = column[ExternalId[KifiInstallation]]("installation_id", O.Nullable)
    def originalJson = column[Option[JsValue]]("original_json", O.Nullable)
    def tagIds = column[String]("tag_ids", O.Nullable) // Comma separated list of references to `collection.id`
    def libraryId = column[Id[Library]]("library_id", O.Nullable)
    def createdDate = column[DateTime]("created_date", O.Nullable)
    def hashtags = column[String]("hashtags", O.Nullable)
    def * = (id.?, userId, createdAt, updatedAt, url, title.?, isPrivate, importId.?, source, kifiInstallationId.?, originalJson, state, tagIds.?, libraryId.?, createdDate.?, hashtags.?) <> ((RawKeep.apply _).tupled, RawKeep.unapply _)
  }

  def table(tag: Tag) = new RawKeepTable(tag)
  initTable()

  def deleteCache(model: com.keepit.model.RawKeep)(implicit session: com.keepit.common.db.slick.DBSession.RSession): Unit = {}
  def invalidateCache(model: com.keepit.model.RawKeep)(implicit session: com.keepit.common.db.slick.DBSession.RSession): Unit = {}

  private def sanitizeRawKeep(rawKeep: RawKeep): RawKeep = {
    val titleTrimmed = if (rawKeep.title.map(_.length).getOrElse(0) > 2048) {
      rawKeep.copy(title = rawKeep.title.map(_.trim.take(2048)))
    } else rawKeep

    val urlTrimmed = if (titleTrimmed.url.length > 2048) {
      rawKeep.copy(url = rawKeep.url.take(2048))
    } else rawKeep

    urlTrimmed
  }

  def setState(rawKeepId: Id[RawKeep], state: State[RawKeep])(implicit session: RWSession): Boolean = {
    (for (row <- rows if row.id === rawKeepId) yield (row.updatedAt, row.state)).update((clock.now, state)) == 1
  }

  def getUnprocessedAndMarkAsImporting(batchSize: Int)(implicit session: RSession): Seq[RawKeep] = {
    //StaticQuery.queryNA[RawKeep](s"select ")
    val records = (for (row <- rows if row.state === RawKeepStates.ACTIVE) yield row)
      .sortBy(row => row.id.asc)
      .take(batchSize)
      .list
    if (records.nonEmpty) {
      (for (row <- rows if row.id inSet records.map(_.id.get).toSet) yield row.state).update(RawKeepStates.IMPORTING)
    }
    records
  }

  def getOldUnprocessed(batchSize: Int, before: DateTime)(implicit session: RSession): Seq[RawKeep] = {
    (for (row <- rows if row.state === RawKeepStates.IMPORTING && row.createdAt < before) yield row)
      .sortBy(row => row.id.desc)
      .take(batchSize)
      .list
  }

  def insertAll(rawKeeps: Seq[RawKeep])(implicit session: RWSession): Try[Int] = {
    Try(rows.insertAll(rawKeeps.map(sanitizeRawKeep): _*).getOrElse(0))
  }

  def insertOne(rawKeep: RawKeep)(implicit session: RWSession): Try[Boolean] = {
    Try(rows.insert(sanitizeRawKeep(rawKeep)) == 1)
  }
}
