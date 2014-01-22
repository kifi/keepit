package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.db.{ExternalId, State, Id}
import com.keepit.common.time.{Clock, DEFAULT_DATE_TIME_ZONE}
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
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[RawKeep](db, "raw_keep") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def url = column[String]("url", O.NotNull)
    def title = column[String]("title", O.Nullable)
    def isPrivate = column[Boolean]("is_private", O.NotNull)
    def importId = column[String]("import_id", O.Nullable)
    def source = column[BookmarkSource]("source", O.NotNull)
    def kifiInstallationId = column[ExternalId[KifiInstallation]]("installation_id", O.Nullable)
    def originalJson = column[JsValue]("original_json", O.Nullable)

    def * = id.? ~ userId ~ createdAt ~ updatedAt ~ url ~ title.? ~ isPrivate ~ importId.? ~ source ~ kifiInstallationId.? ~ originalJson.? ~ state <> (RawKeep, RawKeep.unapply _)

    def forInsert = userId ~ createdAt ~ updatedAt ~ url ~ title.? ~ isPrivate ~ importId.? ~ source ~ kifiInstallationId.? ~ originalJson.? ~ state <> (
      { t => RawKeep(None, t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11)},
      {(c: RawKeep) => Some((c.userId, c.createdAt, c.updatedAt, c.url, c.title, c.isPrivate, c.importId, c.source, c.installationId, c.originalJson, c.state))}
    )
  }

  def deleteCache(model: com.keepit.model.RawKeep)(implicit session: com.keepit.common.db.slick.DBSession.RSession): Unit = { }
  def invalidateCache(model: com.keepit.model.RawKeep)(implicit session: com.keepit.common.db.slick.DBSession.RSession): Unit = { }


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
    (for (row <- table if row.id === rawKeepId) yield row.updatedAt ~ row.state).update((clock.now, state)) == 1
  }

  def getUnprocessedAndMarkAsImporting(batchSize: Int)(implicit session: RSession): Seq[RawKeep] = {
    //StaticQuery.queryNA[RawKeep](s"select ")
    val records = (for (row <- table if row.state === RawKeepStates.ACTIVE) yield row)
      .sortBy(row => row.id.asc)
      .take(batchSize)
      .list
    (for (row <- table if row.id inSet records.map(_.id.get).toSet) yield row.state).update(RawKeepStates.IMPORTING)
    records
  }

  def getOldUnprocessed(batchSize: Int, before: DateTime)(implicit session: RSession): Seq[RawKeep] = {
    (for (row <- table if row.state === RawKeepStates.IMPORTING && row.createdAt < before) yield row)
      .sortBy(row => row.id.desc)
      .take(batchSize)
      .list
  }

  def insertAll(rawKeeps: Seq[RawKeep])(implicit session: RWSession): Try[Int] = {
    Try(table.forInsert.insertAll(rawKeeps.map(sanitizeRawKeep): _*).getOrElse(0))
  }

  def insertOne(rawKeep: RawKeep)(implicit session: RWSession): Try[Boolean] = {
    Try(table.forInsert.insert(sanitizeRawKeep(rawKeep)) == 1)
  }
}
