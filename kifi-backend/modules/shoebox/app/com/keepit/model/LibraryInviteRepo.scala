package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time.Clock
import com.keepit.common.util.Paginator
import org.joda.time.DateTime

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[LibraryInviteRepoImpl])
trait LibraryInviteRepo extends Repo[LibraryInvite] with RepoWithDelete[LibraryInvite] {
  def getWithLibraryId(libraryId: Id[Library], excludeState: Option[State[LibraryInvite]] = Some(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], includeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.ACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def countWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int
  def countWithLibraryIdAndEmail(libraryId: Id[Library], email: EmailAddress, excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int
  def countDistinctWithUserId(userId: Id[User])(implicit session: RSession): Int
  def getByUser(userId: Id[User], excludeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[(LibraryInvite, Library)]
  def getByEmailAddress(email: EmailAddress, excludeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[LibraryInvite]
  def getByLibraryIdAndAuthToken(libraryId: Id[Library], authToken: String, excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Seq[LibraryInvite]
  def pageInviteesByLibraryId(libraryId: Id[Library], offset: Int, limit: Int, includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])]
  def getByLibraryIdAndInviterId(libraryId: Id[Library], inviterId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[LibraryInvite]
  def getLastSentByLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite]
  def getLastSentByLibraryIdAndInviterIdAndUserId(libraryId: Id[Library], inviterId: Id[User], userId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite]
  def getLastSentByLibraryIdAndInviterIdAndEmail(libraryId: Id[Library], inviterId: Id[User], email: EmailAddress, includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite]
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
    def inviterId = column[Id[User]]("inviter_id", O.NotNull)
    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def access = column[LibraryAccess]("access", O.NotNull)
    def emailAddress = column[Option[EmailAddress]]("email_address", O.Nullable)
    def authToken = column[String]("auth_token", O.NotNull)
    def message = column[Option[String]]("message", O.Nullable)
    def * = (id.?, libraryId, inviterId, userId, emailAddress, access, createdAt, updatedAt, state, authToken, message) <> ((LibraryInvite.apply _).tupled, LibraryInvite.unapply)
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

  def getWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], includeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.ACTIVE))(implicit session: RSession): Seq[LibraryInvite] = {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && b.state.inSet(includeSet)) yield b).sortBy(_.createdAt).list
  }

  def countWithLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int = {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && !b.state.inSet(excludeSet)) yield b).sortBy(_.createdAt).length.run
  }
  def countWithLibraryIdAndEmail(libraryId: Id[Library], email: EmailAddress, excludeSet: Set[State[LibraryInvite]] = Set(LibraryInviteStates.INACTIVE))(implicit session: RSession): Int = {
    (for (b <- rows if b.libraryId === libraryId && b.emailAddress === email && !b.state.inSet(excludeSet)) yield b).sortBy(_.createdAt).length.run
  }

  def countDistinctWithUserId(userId: Id[User])(implicit session: RSession): Int = {
    //(for (b <- rows if b.userId === userId && !b.state.inSet(excludeSet)) yield b).groupBy(x => x).map(_._1).length.run
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"select count(distinct library_id) from library_invite where user_id=$userId and state='active'"
    query.as[Int].firstOption.getOrElse(0)
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

  def getByEmailAddress(emailAddress: EmailAddress, excludeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[LibraryInvite] = {
    (for (b <- rows if b.emailAddress === emailAddress && !b.state.inSet(excludeStates)) yield b).list
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

  def pageInviteesByLibraryId(libraryId: Id[Library], offset: Int, limit: Int, includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[(Either[Id[User], EmailAddress], Set[LibraryInvite])] = {
    val invitees = {
      val invitesGroupedByInvitee = (for (r <- rows if r.libraryId === libraryId && r.state.inSet(includeStates)) yield r).groupBy(r => (r.userId, r.emailAddress))
      val inviteesWithLastInvitedAt = invitesGroupedByInvitee.map { case ((userId, emailAddress), invites) => (userId, emailAddress, invites.map(_.createdAt).min) }
      val sortedInvitees = inviteesWithLastInvitedAt.sortBy { case (userId, emailAddress, firstInvitedAt) => (userId.isDefined, firstInvitedAt) }
      sortedInvitees.drop(offset).take(limit).map { case (userId, emailAddress, firstInvitedAt) => (userId, emailAddress) }.list
    }

    val (userIds, emailAddresses) = {
      val userInvitees = invitees.filter { case (userId, _) => userId.isDefined }
      val emailInvitees = invitees.filter { case (_, email) => email.isDefined }
      (userInvitees.map(_._1.get), emailInvitees.map(_._2.get))
    }

    lazy val invitesByUserId = {
      (for (r <- rows if r.libraryId === libraryId && r.state.inSet(includeStates) && r.userId.inSet(userIds)) yield r).list
    }.groupBy(_.userId.get).mapValues(_.toSet)

    lazy val invitesByEmailAddress = {
      (for (r <- rows if r.libraryId === libraryId && r.state.inSet(includeStates) && r.emailAddress.inSet(emailAddresses)) yield r).list
    }.groupBy(_.emailAddress.get).mapValues(_.toSet)

    userIds.map(userId => Left(userId) -> invitesByUserId(userId)) ++ emailAddresses.map(emailAddress => Right(emailAddress) -> invitesByEmailAddress(emailAddress))
  }

  private def getByLibraryIdAndInviterIdCompiled(libraryId: Column[Id[Library]], inviterId: Column[Id[User]], includeStates: Set[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.inviterId === inviterId && b.state.inSet(includeStates)) yield b).sortBy(_.createdAt)
  }

  def getByLibraryIdAndInviterId(libraryId: Id[Library], inviterId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Seq[LibraryInvite] = {
    getByLibraryIdAndInviterIdCompiled(libraryId, inviterId, includeStates).list
  }

  private def getByLibraryIdAndInviterIdAndUserIdCompiled(libraryId: Column[Id[Library]], inviterId: Column[Id[User]], userId: Column[Id[User]], includeStates: Set[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.inviterId === inviterId && b.userId === userId && b.state.inSet(includeStates)) yield b).sortBy(_.createdAt.desc)
  }
  def getLastSentByLibraryIdAndInviterIdAndUserId(libraryId: Id[Library], inviterId: Id[User], userId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite] = {
    getByLibraryIdAndInviterIdAndUserIdCompiled(libraryId, inviterId, userId, includeStates).firstOption
  }

  private def getByLibraryIdAndInviterIdAndEmailCompiled(libraryId: Column[Id[Library]], inviterId: Column[Id[User]], email: Column[EmailAddress], includeStates: Set[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.inviterId === inviterId && b.emailAddress === email && b.state.inSet(includeStates)) yield b).sortBy(_.createdAt.desc)
  }
  def getLastSentByLibraryIdAndInviterIdAndEmail(libraryId: Id[Library], inviterId: Id[User], email: EmailAddress, includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite] = {
    getByLibraryIdAndInviterIdAndEmailCompiled(libraryId, inviterId, email, includeStates).firstOption
  }

  private def getLastSentByLibraryIdAndUserIdCompiled(libraryId: Column[Id[Library]], userId: Column[Id[User]], includeStates: Set[State[LibraryInvite]]) = Compiled {
    (for (b <- rows if b.libraryId === libraryId && b.userId === userId && b.state.inSet(includeStates)) yield b).sortBy(_.createdAt.desc)
  }
  def getLastSentByLibraryIdAndUserId(libraryId: Id[Library], userId: Id[User], includeStates: Set[State[LibraryInvite]])(implicit session: RSession): Option[LibraryInvite] = {
    getLastSentByLibraryIdAndUserIdCompiled(libraryId, userId, includeStates).firstOption
  }
}
