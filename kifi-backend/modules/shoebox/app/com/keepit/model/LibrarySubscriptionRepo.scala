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
  def getByLibraryId(libraryId: Id[Library], excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE))(implicit session: RSession): Seq[LibrarySubscription]
  def getByLibraryIdAndName(libraryId: Id[Library], name: String, excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE))(implicit session: RSession): Option[LibrarySubscription]
  def getByLibraryIdAndTrigger(libraryId: Id[Library], trigger: SubscriptionTrigger, excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE))(implicit session: RSession): Seq[LibrarySubscription]
}

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
    }
  )

  type RepoImpl = LibrarySubscriptionTable

  class LibrarySubscriptionTable(tag: Tag) extends RepoTable[LibrarySubscription](db, tag, "library_subscription") {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def name = column[String]("name", O.NotNull)
    def trigger = column[SubscriptionTrigger]("trigger", O.NotNull)
    def info = column[SubscriptionInfo]("info", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, libraryId, name, trigger, info) <> ((LibrarySubscription.apply _).tupled, LibrarySubscription.unapply _)
  }

  def table(tag: Tag) = new LibrarySubscriptionTable(tag)
  initTable()

  def deleteCache(model: LibrarySubscription)(implicit session: RSession): Unit = {}
  def invalidateCache(model: LibrarySubscription)(implicit session: RSession): Unit = {}

  def getByLibraryId(libraryId: Id[Library], excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE, LibrarySubscriptionStates.DISABLED))(implicit session: RSession): Seq[LibrarySubscription] = {
    (for (c <- rows if c.libraryId === libraryId && !c.state.inSet(excludeStates)) yield c).list
  }

  def getByLibraryIdAndName(libraryId: Id[Library], name: String, excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE, LibrarySubscriptionStates.DISABLED))(implicit session: RSession): Option[LibrarySubscription] = {
    (for (c <- rows if c.libraryId === libraryId && c.name.trim.toLowerCase === name.trim.toLowerCase && !c.state.inSet(excludeStates)) yield c).firstOption
  }

  def getByLibraryIdAndTrigger(libraryId: Id[Library], trigger: SubscriptionTrigger, excludeStates: Set[State[LibrarySubscription]] = Set(LibrarySubscriptionStates.INACTIVE, LibrarySubscriptionStates.DISABLED))(implicit session: RSession): Seq[LibrarySubscription] = {
    (for (c <- rows if c.libraryId === libraryId && c.trigger === trigger && !c.state.inSet(excludeStates)) yield c).list
  }
}
