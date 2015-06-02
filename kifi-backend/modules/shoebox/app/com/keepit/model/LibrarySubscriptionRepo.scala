package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id, States }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ Repo, DataBaseComponent, DbRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.json.simple.JSONObject
import play.api.libs.json._

import scala.slick.lifted.Tag

@ImplementedBy(classOf[LibrarySubscriptionRepoImpl])
trait LibrarySubscriptionRepo extends Repo[LibrarySubscription] {
  def getByLibraryId(id: Id[Library])(implicit session: RSession): Seq[LibrarySubscription]
  def getByLibraryIdAndTrigger(id: Id[Library], trigger: SubscriptionTrigger)(implicit session: RSession): Seq[LibrarySubscription]
}

object LibrarySubscriptionStates extends States[LibrarySubscription]

@Singleton
class LibrarySubscriptionRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[LibrarySubscription] with LibrarySubscriptionRepo with Logging {
  import db.Driver.simple._

  implicit val triggerColumnType = MappedColumnType.base[SubscriptionTrigger, String](
    { trigger => trigger.value }, { value => SubscriptionTrigger(value) }
  )

  implicit val infoColumnType = MappedColumnType.base[SubscriptionInfo, JsValue](
    { subscription => Json.toJson(subscription) },
    { body =>
      Json.fromJson[SubscriptionInfo](body) match {
        case success: JsSuccess[SubscriptionInfo] => success.value
        case failure: JsError => throw new JsResultException(failure.errors)
      }
    })

  type RepoImpl = LibrarySubscriptionTable

  class LibrarySubscriptionTable(tag: Tag) extends RepoTable[LibrarySubscription](db, tag, "library_webhook") {
    def trigger = column[SubscriptionTrigger]("trigger", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def info = column[SubscriptionInfo]("info", O.NotNull) // this will be changed to SubscriptionInfo once I figure out serialization

    def * = (id.?, createdAt, updatedAt, state, trigger, libraryId, info) <> ((LibrarySubscription.apply _).tupled, LibrarySubscription.unapply _)
  }

  def table(tag: Tag) = new LibrarySubscriptionTable(tag)
  initTable()

  def deleteCache(model: LibrarySubscription)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LibrarySubscription)(implicit session: RSession): Unit = {}

  def getByLibraryId(id: Id[Library])(implicit session: RSession): Seq[LibrarySubscription] = {
    (for (c <- rows if c.libraryId === id) yield c).list
  }

  def getByLibraryIdAndTrigger(id: Id[Library], trigger: SubscriptionTrigger)(implicit session: RSession): Seq[LibrarySubscription] = {
    (for (c <- rows if c.libraryId === id && c.trigger === trigger) yield c).list
  }
}
