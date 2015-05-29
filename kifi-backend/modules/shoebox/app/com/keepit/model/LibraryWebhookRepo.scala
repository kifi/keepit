package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ Repo, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.json.simple.JSONObject
import play.api.libs.json.JsValue

import scala.slick.lifted.Tag

@ImplementedBy(classOf[LibraryWebhookRepoImpl])
trait LibraryWebhookRepo extends Repo[LibraryWebhook] {
  def getByLibraryId(id: Id[Library])(implicit session: RSession): Seq[LibraryWebhook]
  def getByLibraryIdAndTrigger(id: Id[Library], trigger: WebhookTrigger)(implicit session: RSession): Seq[LibraryWebhook]
}

@Singleton
class LibraryWebhookRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[LibraryWebhook] with LibraryWebhookRepo with Logging {
  import db.Driver.simple._

  type RepoImpl = LibraryWebhookTable

  class LibraryWebhookTable(tag: Tag) extends RepoTable[LibraryWebhook](db, tag, "library_webhook") {

    def trigger = column[WebhookTrigger]("action", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def action = column[JsValue]("dest", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, trigger, libraryId, action) <> ((LibraryWebhook.apply _).tupled, LibraryWebhook.unapply _)
  }

  def table(tag: Tag) = new LibraryWebhookTable(tag)
  initTable()

  def deleteCache(model: LibraryWebhook)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LibraryWebhook)(implicit session: RSession): Unit = {}

  def getByLibraryId(id: Id[Library])(implicit session: RSession): Seq[LibraryWebhook] = {
    (for (c <- rows if c.libraryId === id) yield c).list
  }

  def getByLibraryIdAndTrigger(id: Id[Library], trigger: WebhookTrigger)(implicit session: RSession): Seq[LibraryWebhook] = {
    (for (c <- rows if c.libraryId === id && c.trigger === trigger) yield c).list
  }

}
