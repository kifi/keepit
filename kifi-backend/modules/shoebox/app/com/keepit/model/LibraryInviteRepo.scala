package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ State, ExternalId, Id }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryInviteRepoImpl])
trait LibraryInviteRepo extends Repo[LibraryInvite] with RepoWithDelete[LibraryInvite] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def countWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int
  def getByUser(userId: Id[User], excludeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[(LibraryInvite, Library)]
  def getByLibraryIdAndAuthToken(libraryId: Id[Library], authToken: String, excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
}

@Singleton
class LibraryInviteRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val libraryRepo: LibraryRepoImpl,
  val inviteIdCache: LibraryInviteIdCache)
    extends DbRepo[LibraryInvite] with DbRepoWithDelete[LibraryInvite] with LibraryInviteRepo with Logging {

  import scala.slick.lifted.Query
  import db.Driver.simple._

  type RepoImpl = LibraryInviteTable

  class LibraryInviteTable(tag: Tag) extends RepoTable[LibraryInvite](db, tag, "library_invite") {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def emailAddress = column[EmailAddress]("email_address", O.Nullable)
    def authToken = column[String]("auth_token", O.NotNull)
    def passPhrase = column[String]("pass_phrase", O.NotNull)
    def message = column[String]("message", O.Nullable)
    def * = (id.?, libraryId, ownerId, userId.?, emailAddress.?, access, createdAt, updatedAt, state, authToken, passPhrase, message.?) <> ((LibraryInvite.apply _).tupled, LibraryInvite.unapply)
  }

  def table(tag: Tag) = new LibraryInviteTable(tag)

  initTable()

  override def get(id: Id[LibraryInvite])(implicit session: RSession): LibraryInvite = {
    inviteIdCache.getOrElse(LibraryInviteIdKey(id)) {
      getCompiled(id).first
    }
  }

  private def getWithLibraryIdCompiled(libraryId: Column[Id[Library]], excludeState: Option[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt)
  }
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite] = {
    getWithLibraryIdCompiled(libraryId, excludeState).list
  }

  private def getWithUserIdCompiled(userId: Column[Id[User]], excludeState: Option[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt)
  }
  def getWithUserId(userId: Id[User], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite] = {
    getWithUserIdCompiled(userId, excludeState).list
  }

  private def getWithLibraryIdAndUserIdCompiled(libraryId: Column[Id[Library]], userId: Column[Id[User]], excludeState: Option[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && b.state =!= excludeState.orNull) yield b).sortBy(_.createdAt)
  }
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite] = {
    getWithLibraryIdAndUserIdCompiled(libraryId, userId, excludeState).list
  }

  def countWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int = {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && !b.state.inSet(excludeSet)) yield b).sortBy(_.createdAt).length.run
  }
  def getByLibraryIdAndAuthToken(libraryId: Id[Library], authToken: String, excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite] = {
    (for (b <- rows if b.libraryId === libraryId && b.authToken === authToken && !b.state.inSet(excludeSet)) yield b).sortBy(_.createdAt).list
  }

  override def deleteCache(libInv: LibraryInvite)(implicit session: RSession): Unit = {
    libInv.id.map { id =>
      inviteIdCache.remove(LibraryInviteIdKey(id))
    }
  }

  def getByUser(userId: Id[User], excludeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[(LibraryInvite, Library)] = {
    val q = for {
      li <- rows if li.userId === userId && !li.state.inSet(excludeStates)
      lib <- libraryRepo.rows if lib.id === li.libraryId && lib.state === LibraryStates.ACTIVE
    } yield (li, lib)
    q.list
  }

  override def invalidateCache(libInv: LibraryInvite)(implicit session: RSession): Unit = {
    libInv.id.map { id =>
      if (libInv.state == LibraryInviteStates.INACTIVE) {
        deleteCache(libInv)
      } else {
        inviteIdCache.set(LibraryInviteIdKey(id), libInv)
      }
    }
  }

}
