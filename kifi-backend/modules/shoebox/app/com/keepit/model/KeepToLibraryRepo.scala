package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[KeepToLibraryRepoImpl])
trait KeepToLibraryRepo extends Repo[KeepToLibrary] {
  def countByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getAllByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def getCountByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getCountsByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Int]
  def getByLibraryId(libraryId: Id[Library], offset: Offset, limit: Limit, excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]
  def getAllByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]
  def getAllByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Seq[KeepToLibrary]]

  def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def getByKeepIdAndLibraryId(keepId: Id[Keep], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Option[KeepToLibrary]

  def getVisibileFirstOrderImplicitKeeps(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Set[Id[Keep]]

  def activate(model: KeepToLibrary)(implicit session: RWSession): KeepToLibrary
  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit
}

@Singleton
class KeepToLibraryRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends KeepToLibraryRepo with DbRepo[KeepToLibrary] with Logging {

  override def deleteCache(orgMember: KeepToLibrary)(implicit session: RSession) {}
  override def invalidateCache(orgMember: KeepToLibrary)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = KeepToLibraryTable
  class KeepToLibraryTable(tag: Tag) extends RepoTable[KeepToLibrary](db, tag, "keep_to_library") {
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def addedAt = column[DateTime]("added_at", O.NotNull)
    def addedBy = column[Id[User]]("added_by", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def isPrimary = column[Option[Boolean]]("is_primary", O.Nullable) // trueOrNull
    def keepOwner = column[Id[User]]("keep_owner", O.NotNull)
    def libraryVisibility = column[LibraryVisibility]("visibility", O.NotNull)
    def libraryOrganizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, keepId, libraryId, addedAt, addedBy, uriId, isPrimary, keepOwner, libraryVisibility, libraryOrganizationId) <> ((KeepToLibrary.applyFromDbRow _).tupled, KeepToLibrary.unapplyToDbRow)
  }

  def table(tag: Tag) = new KeepToLibraryTable(tag)
  initTable()

  private def getByKeepIdHelper(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && !row.state.inSet(excludeStates)) yield row
  }
  def countByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int = {
    getByKeepIdHelper(keepId, excludeStates).run.length
  }
  def getAllByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByKeepIdHelper(keepId, excludeStates).list
  }

  private def getByLibraryIdsHelper(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.libraryId.inSet(libraryIds) && !row.state.inSet(excludeStates)) yield row
  }
  private def getByLibraryIdHelper(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]])(implicit session: RSession) = {
    getByLibraryIdsHelper(Set(libraryId), excludeStates)
  }
  def getCountByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int = {
    getByLibraryIdHelper(libraryId, excludeStates).run.length
  }
  def getCountsByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Int] = {
    // TODO(ryan): This needs to use a cache, and fall back on a single monster query, not a bunch of tiny queries
    libraryIds.map(libId => libId -> getCountByLibraryId(libId, excludeStates)).toMap
  }
  def getByLibraryId(libraryId: Id[Library], offset: Offset, limit: Limit, excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByLibraryIdHelper(libraryId, excludeStates).drop(offset.value).take(limit.value).list
  }
  def getAllByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByLibraryIdHelper(libraryId, excludeStates).list
  }
  def getAllByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Seq[KeepToLibrary]] = {
    getByLibraryIdsHelper(libraryIds, excludeStates).list.groupBy(_.libraryId)
  }

  private def getByKeepIdAndLibraryIdHelper(keepId: Id[Keep], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.keepId === keepId && row.libraryId === libraryId && !row.state.inSet(excludeStates)) yield row
  }
  def getByKeepIdAndLibraryId(keepId: Id[Keep], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Option[KeepToLibrary] = {
    getByKeepIdAndLibraryIdHelper(keepId, libraryId, excludeStates).firstOption
  }

  private def getByUserIdAndLibraryIdHelper(userId: Id[User], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]])(implicit session: RSession) = {
    for (row <- rows if row.addedBy === userId && row.libraryId === libraryId && !row.state.inSet(excludeStates)) yield row
  }
  def countByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int = {
    getByUserIdAndLibraryIdHelper(userId, libraryId, excludeStates).run.length
  }
  def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByUserIdAndLibraryIdHelper(userId, libraryId, excludeStates).list
  }

  def getVisibileFirstOrderImplicitKeeps(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Set[Id[Keep]] = {
    // An implicit keep is one that you have access to indirectly (e.g., through a library you follow, or an org you are a member of,
    // or the keep is published so anyone can access it.
    // A first-order implicit keep is one that only takes a single step to get to: this only happens if you are a member of a library
    // where that keep exists
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select ktl.keep_id from bookmark bm, library_membership lm, keep_to_library ktl
                  where bm.uri_id = $uriId and bm.state = '#${KeepStates.ACTIVE}'
                  and lm.user_id = $userId and lm.state = '#${LibraryMembershipStates.ACTIVE}'
                  and ktl.keep_id = bm.id and ktl.library_id = lm.library_id and ktl.state = '#${KeepToLibraryStates.ACTIVE}'"""
    q.as[Id[Keep]].list.toSet
  }

  def activate(model: KeepToLibrary)(implicit session: RWSession): KeepToLibrary = {
    save(model.withState(KeepToLibraryStates.ACTIVE))
  }
  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit = {
    save(model.withState(KeepToLibraryStates.INACTIVE))
  }
}
