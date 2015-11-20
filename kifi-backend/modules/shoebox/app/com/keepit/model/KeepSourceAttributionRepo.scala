package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime
import play.api.libs.json._

@ImplementedBy(classOf[KeepSourceAttributionRepoImpl])
trait KeepSourceAttributionRepo extends DbRepo[KeepSourceAttribution]

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
    Some((attr.id, attr.createdAt, attr.updatedAt, attrType, js, attr.state))
  }

  def applyFromDbRow(id: Option[Id[KeepSourceAttribution]], createdAt: DateTime, updatedAt: DateTime, attrType: KeepAttributionType, attrJson: JsValue, state: State[KeepSourceAttribution]) = {
    val attr = SourceAttribution.fromJson(attrType, attrJson).get
    KeepSourceAttribution(id, createdAt, updatedAt, attr, state)
  }

  class KeepSourceAttributionTable(tag: Tag) extends RepoTable[KeepSourceAttribution](db, tag, "keep_source_attribution") {
    def attributionType = column[KeepAttributionType]("attr_type", O.NotNull)
    def attributionJson = column[JsValue]("attr_json", O.NotNull)
    def * = (id.?, createdAt, updatedAt, attributionType, attributionJson, state) <> ((applyFromDbRow _).tupled, unapplyToDbRow _)
  }

  def table(tag: Tag) = new KeepSourceAttributionTable(tag)
  initTable()

  def invalidateCache(keep: KeepSourceAttribution)(implicit session: RSession): Unit = {}

  def deleteCache(uri: KeepSourceAttribution)(implicit session: RSession): Unit = {}

}

