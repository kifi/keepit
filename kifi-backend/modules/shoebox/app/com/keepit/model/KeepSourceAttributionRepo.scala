package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
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
    { value => KeepAttributionType(value) }
  )

  class KeepSourceAttributionTable(tag: Tag) extends RepoTable[KeepSourceAttribution](db, tag, "keep_source_attribution") {
    def attributionType = column[KeepAttributionType]("attr_type", O.NotNull)
    def attributionJson = column[JsValue]("attr_json", O.Nullable)
    def * = (id.?, createdAt, updatedAt, attributionType, attributionJson.?, state) <> ((KeepSourceAttribution.apply _).tupled, KeepSourceAttribution.unapply _)
  }

  def table(tag: Tag) = new KeepSourceAttributionTable(tag)
  initTable()

  def invalidateCache(keep: KeepSourceAttribution)(implicit session: RSession): Unit = {}

  def deleteCache(uri: KeepSourceAttribution)(implicit session: RSession): Unit = {}

}

