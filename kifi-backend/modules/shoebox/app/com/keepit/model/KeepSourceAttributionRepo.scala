package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.social.twitter.TwitterStatusId
import org.joda.time.DateTime
import play.api.libs.json._

@ImplementedBy(classOf[KeepSourceAttributionRepoImpl])
trait KeepSourceAttributionRepo extends DbRepo[KeepSourceAttribution] {
  def getByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], SourceAttribution]
  def save(keepId: Id[Keep], attribution: SourceAttribution)(implicit session: RWSession): KeepSourceAttribution
}

@Singleton
class KeepSourceAttributionRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[KeepSourceAttribution] with KeepSourceAttributionRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = KeepSourceAttributionTable

  implicit def attributionTypeMapper = MappedColumnType.base[KeepAttributionType, String](
    { attr => attr.name },
    { name => KeepAttributionType.fromString(name).get }
  )

  def unapplyToDbRow(attr: KeepSourceAttribution) = {
    val (attrType, js) = SourceAttribution.toJson(attr.attribution)
    Some((attr.id, attr.createdAt, attr.updatedAt, attr.keepId, attrType, js, attr.state))
  }

  def applyFromDbRow(id: Option[Id[KeepSourceAttribution]], createdAt: DateTime, updatedAt: DateTime, keepId: Id[Keep], attrType: KeepAttributionType, attrJson: JsValue, state: State[KeepSourceAttribution]) = {
    val attr = SourceAttribution.fromJson(attrType, attrJson).get
    KeepSourceAttribution(id, createdAt, updatedAt, keepId, attr, state)
  }

  class KeepSourceAttributionTable(tag: Tag) extends RepoTable[KeepSourceAttribution](db, tag, "keep_source_attribution") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def attributionType = column[KeepAttributionType]("attr_type", O.NotNull)
    def attributionJson = column[JsValue]("attr_json", O.NotNull)
    def * = (id.?, createdAt, updatedAt, keepId, attributionType, attributionJson, state) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new KeepSourceAttributionTable(tag)
  initTable()

  def invalidateCache(keep: KeepSourceAttribution)(implicit session: RSession): Unit = {}

  def deleteCache(uri: KeepSourceAttribution)(implicit session: RSession): Unit = {}

  private def activeRows = rows.filter(row => row.state === KeepSourceAttributionStates.ACTIVE)

  def getByKeepIds(keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], SourceAttribution] = {
    activeRows.filter(_.keepId inSet keepIds).list.map(att => att.keepId -> att.attribution).toMap
  }

  def save(keepId: Id[Keep], attribution: SourceAttribution)(implicit session: RWSession): KeepSourceAttribution = {
    val keepAttributionOpt = rows.filter(_.keepId === keepId).firstOption
    save(KeepSourceAttribution(id = keepAttributionOpt.flatMap(_.id), keepId = keepId, attribution = attribution))
  }
}

