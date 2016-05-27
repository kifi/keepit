package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import play.api.libs.json.{ JsArray, JsValue }
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
  def getByKeep(keep: Keep)(implicit session: RSession): Seq[RawKeep]
}

@Singleton
class RawKeepRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[RawKeep] with RawKeepRepo {
  import db.Driver.simple._

  type RepoImpl = RawKeepTable
  class RawKeepTable(tag: Tag) extends RepoTable[RawKeep](db, tag, "raw_keep") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def title = column[Option[String]]("title", O.Nullable)
    def isPrivate = column[Boolean]("is_private", O.Nullable)
    def importId = column[Option[String]]("import_id", O.Nullable)
    def source = column[KeepSource]("source", O.NotNull)
    def kifiInstallationId = column[Option[ExternalId[KifiInstallation]]]("installation_id", O.Nullable)
    def originalJson = column[Option[JsValue]]("original_json", O.Nullable)
    def libraryId = column[Option[Id[Library]]]("library_id", O.Nullable)
    def createdDate = column[Option[DateTime]]("created_date", O.Nullable)
    def keepTags = column[Option[JsArray]]("keep_tags", O.Nullable)
    def * = (id.?, userId, createdAt, updatedAt, url, title, isPrivate, importId, source, kifiInstallationId, originalJson, state, libraryId, createdDate, keepTags) <> ((RawKeep.applyFromDbRow _).tupled, RawKeep.unapplyToDbRow)
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
      .sortBy(row => row.createdDate.desc)
      .take(batchSize)
      .list
    if (records.nonEmpty) {
      (for (row <- rows if row.id inSet records.map(_.id.get).toSet) yield row.state).update(RawKeepStates.IMPORTING)
    }
    records
  }

  def getOldUnprocessed(batchSize: Int, before: DateTime)(implicit session: RSession): Seq[RawKeep] = {
    (for (row <- rows if row.state === RawKeepStates.IMPORTING && row.createdAt < before) yield row)
      .sortBy(row => row.createdAt.desc)
      .take(batchSize)
      .list
  }

  def insertAll(rawKeeps: Seq[RawKeep])(implicit session: RWSession): Try[Int] = {
    Try(rows.insertAll(rawKeeps.map(sanitizeRawKeep): _*).getOrElse(0))
  }

  def insertOne(rawKeep: RawKeep)(implicit session: RWSession): Try[Boolean] = {
    Try(rows.insert(sanitizeRawKeep(rawKeep)) == 1)
  }

  def getByKeep(keep: Keep)(implicit session: RSession): Seq[RawKeep] = {
    rows.filter(r => r.userId === keep.userId && r.url === keep.url && r.source === keep.source && r.createdDate === keep.keptAt).list
  }
}
