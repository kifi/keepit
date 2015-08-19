package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import scala.slick.jdbc.{ PositionedResult, GetResult, StaticQuery }
import com.keepit.common.db.{ ExternalId, Id, SequenceNumber, State }
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[KeepToLibraryRepoImpl])
trait KeepToLibraryRepo extends Repo[KeepToLibrary] {
  def getByIds(ids: Set[Id[KeepToLibrary]])(implicit session: RSession): Map[Id[KeepToLibrary], KeepToLibrary]

  def countByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getAllByKeepId(keepId: Id[Keep], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def getCountByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Int
  def getCountsByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Int]
  def getAllByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]
  def getAllByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Seq[KeepToLibrary]]

  def getByLibraryIdSorted(libraryId: Id[Library], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Id[Keep]]

  def getByUserIdAndLibraryId(userId: Id[User], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary]

  def getByKeepIdAndLibraryId(keepId: Id[Keep], libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Option[KeepToLibrary]

  def getVisibileFirstOrderImplicitKeeps(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Set[Id[Keep]]

  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit

  // For backwards compatibility with KeepRepo
  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[KeepToLibrary]
  def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[KeepToLibrary]
  def getFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[KeepToLibrary]
  def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[KeepToLibrary]
  def getRecentFromLibraries(libraryIds: Set[Id[Library]], limit: Limit, beforeIdOpt: Option[Id[KeepToLibrary]], afterIdOpt: Option[Id[KeepToLibrary]])(implicit session: RSession): Seq[KeepToLibrary]
  def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]]
  def publishedLibrariesWithMostKeepsSince(limit: Limit, since: DateTime)(implicit session: RSession): Map[Id[Library], Int]
  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]]
  def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String]
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
    def visibility = column[LibraryVisibility]("visibility", O.NotNull)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, keepId, libraryId, addedAt, addedBy, uriId, isPrimary, visibility, organizationId) <> ((KeepToLibrary.applyFromDbRow _).tupled, KeepToLibrary.unapplyToDbRow)
  }

  def table(tag: Tag) = new KeepToLibraryTable(tag)
  initTable()

  // ought to use a cache
  def getByIds(ids: Set[Id[KeepToLibrary]])(implicit session: RSession): Map[Id[KeepToLibrary], KeepToLibrary] = {
    val q = for (row <- rows if row.id.inSet(ids)) yield row
    q.list.map(ktl => ktl.id.get -> ktl).toMap
  }

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
  def getAllByLibraryId(libraryId: Id[Library], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Seq[KeepToLibrary] = {
    getByLibraryIdHelper(libraryId, excludeStates).list
  }
  def getAllByLibraryIds(libraryIds: Set[Id[Library]], excludeStates: Set[State[KeepToLibrary]] = Set(KeepToLibraryStates.INACTIVE))(implicit session: RSession): Map[Id[Library], Seq[KeepToLibrary]] = {
    getByLibraryIdsHelper(libraryIds, excludeStates).list.groupBy(_.libraryId)
  }

  def getByLibraryIdSorted(libraryId: Id[Library], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Id[Keep]] = {
    // N.B.: Slick is NOT GOOD at joins (as of 2015-08-18). This query is hand-written for a reason.
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select k.id from
                  keep_to_library ktl inner join bookmark k on (ktl.keep_id = k.id)
                  where ktl.library_id = $libraryId and ktl.state = 'active' and k.state = 'active'
                  order by k.kept_at desc, k.id desc
                  limit ${limit.value} offset ${offset.value};"""
    q.as[Id[Keep]].list
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

  def deactivate(model: KeepToLibrary)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }

  /**
   * ***************************************************
   * For backwards compatibility with KeepRepo methods *
   * ***************************************************
   */

  def getByLibraryIdAndExcludingVisibility(libId: Id[Library], excludeVisibility: Option[LibraryVisibility], limit: Limit)(implicit session: RSession): Seq[KeepToLibrary] = {
    val q = { for (ktl <- rows if ktl.libraryId === libId && ktl.visibility =!= excludeVisibility.orNull) yield ktl }.take(limit.value)
    q.list
  }
  def getByLibraryIdsAndUriIds(libraryIds: Set[Id[Library]], uriIds: Set[Id[NormalizedURI]])(implicit session: RSession): Seq[KeepToLibrary] = {
    (for (ktl <- rows if ktl.uriId.inSet(uriIds) && ktl.libraryId.inSet(libraryIds) && ktl.state === KeepToLibraryStates.ACTIVE) yield ktl).list
  }
  def getPrimaryByUriAndLibrary(uriId: Id[NormalizedURI], libId: Id[Library])(implicit session: RSession): Option[KeepToLibrary] = {
    // TODO(ryan): this method needs to be deprecated, it doesn't make sense anymore (now we can have the same URI in a lib multiple times)
    (for (ktl <- rows if ktl.uriId === uriId && ktl.libraryId === libId && ktl.isPrimary === true) yield ktl).firstOption
  }

  def getByLibraryWithInconsistentOrgId(libraryId: Id[Library], expectedOrgId: Option[Id[Organization]], limit: Limit)(implicit session: RSession): Set[KeepToLibrary] = {
    expectedOrgId match {
      case None => (for (ktl <- rows if ktl.libraryId === libraryId && ktl.organizationId.isDefined) yield ktl).take(limit.value).list.toSet
      case Some(orgId) => (for (ktl <- rows if ktl.libraryId === libraryId && (ktl.organizationId.isEmpty || ktl.organizationId =!= orgId)) yield ktl).take(limit.value).list.toSet
    }
  }
  def getFromLibrarySince(since: DateTime, library: Id[Library], max: Int)(implicit session: RSession): Seq[KeepToLibrary] = {
    (for (ktl <- rows if ktl.libraryId === library && ktl.state === KeepToLibraryStates.ACTIVE && ktl.addedAt > since) yield ktl).sortBy(ktl => (ktl.addedAt asc, ktl.id)).take(max).list
  }
  def librariesWithMostKeepsSince(count: Int, since: DateTime)(implicit session: RSession): Seq[(Id[Library], Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    // TODO(ryan): rethink this query, do we acually need the load whole set of a library's keeps?
    // Can't we use `library.last_kept`?
    sql"""select b.library_id, count(*) as cnt from keep_to_library b, library l where l.id = b.library_id and l.state='active' and l.visibility='published' and b.added_at > $since group by b.library_id order by count(*) desc limit $count""".as[(Id[Library], Int)].list
  }

  def getRecentFromLibraries(libraryIds: Set[Id[Library]], limit: Limit, beforeIdOpt: Option[Id[KeepToLibrary]], afterIdOpt: Option[Id[KeepToLibrary]])(implicit session: RSession): Seq[KeepToLibrary] = {
    val allLibraryKeeps = for (ktl <- rows if ktl.libraryId.inSet(libraryIds) && ktl.state === KeepToLibraryStates.ACTIVE) yield ktl
    val recentLibraryKeeps = (beforeIdOpt.map(get), afterIdOpt.map(get)) match {
      case (None, None) =>
        allLibraryKeeps
      case (Some(upper), _) =>
        allLibraryKeeps.filter(ktl => ktl.addedAt < upper.addedAt || (ktl.addedAt === upper.addedAt && ktl.keepId < upper.keepId))
      case (None, Some(lower)) =>
        allLibraryKeeps.filter(ktl => ktl.addedAt > lower.addedAt || (ktl.addedAt === lower.addedAt && ktl.keepId > lower.keepId))
    }
    recentLibraryKeeps.sortBy(ktl => (ktl.addedAt desc, ktl.keepId desc)).take(limit.value).list
  }

  def latestKeptAtByLibraryIds(libraryIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Option[DateTime]] = {
    val keepsGroupedByLibrary = (for (r <- rows if r.libraryId.inSet(libraryIds) && r.state === KeepToLibraryStates.ACTIVE) yield r).groupBy(_.libraryId)
    val latestAtByLibrary = keepsGroupedByLibrary.map { case (libraryId, ktls) => (libraryId, ktls.map(k => k.addedAt).max) }.list.toMap
    libraryIds.map { libId => libId -> latestAtByLibrary.getOrElse(libId, None) }.toMap
  }
  def publishedLibrariesWithMostKeepsSince(limit: Limit, since: DateTime)(implicit session: RSession): Map[Id[Library], Int] = {
    val published: LibraryVisibility = LibraryVisibility.PUBLISHED
    val recentKeeps = for (r <- rows if r.state === KeepToLibraryStates.ACTIVE && r.addedAt > since && r.visibility === published) yield r
    val numKeepsByLibrary = recentKeeps.groupBy(_.libraryId).map { case (libraryId, ktls) => (libraryId, ktls.length) }
    numKeepsByLibrary.sortBy { case (libraryId, numKeeps) => (numKeeps desc, libraryId asc) }.take(limit.value).list.toMap
  }
  def getMaxKeepSeqNumForLibraries(libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], SequenceNumber[Keep]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (libIds.isEmpty) {
      Map.empty
    } else {
      val idset = libIds.map { _.id }.mkString("(", ",", ")")
      val q = sql"""select ktl.library_id, max(bm.seq) 
                    from keep_to_library ktl, bookmark bm
                    where bm.id = ktl.keep_id and ktl.library_id in #${idset}
                    group by ktl.library_id"""

      q.as[(Long, Long)].list.map { case (libId, seq) => Id[Library](libId) -> SequenceNumber[Keep](seq) }.toMap
    }
  }
  def recentKeepNotes(libId: Id[Library], limit: Int)(implicit session: RSession): Seq[String] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"""select bm.note
                  from keep_to_library ktl, bookmark bm
                  where bm.id = ktl.keep_id and library_id = $libId and note is not null
                  order by ktl.added_at desc
                  limit $limit"""
    q.as[String].list
  }
}
