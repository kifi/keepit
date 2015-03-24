package com.keepit.cortex.dbmodel

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, SeqNumberDbFunction }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock
import com.keepit.model.{ Library, User, LibraryAccess, LibraryMembership }
import org.joda.time.DateTime

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[CortexLibraryMembershipRepoImpl])
trait CortexLibraryMembershipRepo extends DbRepo[CortexLibraryMembership] with SeqNumberDbFunction[CortexLibraryMembership] {
  def getSince(seq: SequenceNumber[CortexLibraryMembership], limit: Int)(implicit session: RSession): Seq[CortexLibraryMembership]
  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexLibraryMembership]
  def getByLibraryMembershipId(memId: Id[LibraryMembership])(implicit session: RSession): Option[CortexLibraryMembership]
}

@Singleton
class CortexLibraryMembershipRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[CortexLibraryMembership] with CortexLibraryMembershipRepo with SeqNumberDbFunction[CortexLibraryMembership] {

  import db.Driver.simple._

  type RepoImpl = CortexLibraryMembershipTable

  class CortexLibraryMembershipTable(tag: Tag) extends RepoTable[CortexLibraryMembership](db, tag, "cortex_library_membership") with SeqNumberColumn[CortexLibraryMembership] {
    def membershipId = column[Id[LibraryMembership]]("membership_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def access = column[LibraryAccess]("access", O.NotNull)
    def memberSince = column[DateTime]("member_since", O.NotNull)
    def * = (id.?, createdAt, updatedAt, membershipId, libraryId, userId, access, memberSince, state, seq) <> ((CortexLibraryMembership.apply _).tupled, CortexLibraryMembership.unapply)
  }

  def table(tag: Tag) = new CortexLibraryMembershipTable(tag)
  initTable()

  def invalidateCache(lib: CortexLibraryMembership)(implicit session: RSession): Unit = {}

  def deleteCache(lib: CortexLibraryMembership)(implicit session: RSession): Unit = {}

  def getSince(seq: SequenceNumber[CortexLibraryMembership], limit: Int)(implicit session: RSession): Seq[CortexLibraryMembership] = super.getBySequenceNumber(seq, limit)

  def getMaxSeq()(implicit session: RSession): SequenceNumber[CortexLibraryMembership] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val sql = sql"select max(seq) from cortex_library_membership"
    SequenceNumber[CortexLibraryMembership](sql.as[Long].first max 0L)
  }

  def getByLibraryMembershipId(memId: Id[LibraryMembership])(implicit session: RSession): Option[CortexLibraryMembership] =
    (for { r <- rows if r.membershipId === memId } yield r).firstOption
}
