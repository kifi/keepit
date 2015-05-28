package com.keepit.model

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.{DataBaseComponent, DbRepo}
import com.keepit.common.time.Clock
import org.joda.time.DateTime

trait LibraryWebhookRepo extends DbRepo[LibraryWebhook] {
  def save(model: LibraryWebhook): LibraryWebhook
  def getByLibraryid(id: Id[Library]): LibraryWebhook
}

@Singleton
class LibraryWebhookRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock) extends DbRepo[LibraryWebhook] with LibraryWebhookRepo {
  import db.Driver.simple._

  type RepoImpl = LibraryWebhookTable

  class LibraryWebhookTable(tag: Tag) extends RepoTable[LibraryWebhook](db, tag, "library_webhook") {
    def createdAt = column[DateTime]("created_at", O.Nullable)
    def updatedAt = column[DateTime]("updated_at", O.Nullable)
  }

  class LibrarykeepWebhookTable
}
