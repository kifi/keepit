package com.keepit.model.tracking

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.{ Library, User }
import org.joda.time.DateTime

@ImplementedBy(classOf[LibraryViewTrackingRepoImpl])
trait LibraryViewTrackingRepo extends DbRepo[LibraryViewTracking] {
  def getTotalViews(ownerId: Id[User], since: DateTime)(implicit session: RSession): Int
  def getTopViewedLibrariesAndCounts(ownerId: Id[User], since: DateTime, limit: Int)(implicit session: RSession): Map[Id[Library], Int]
}

@Singleton
class LibraryViewTrackingRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[LibraryViewTracking] with LibraryViewTrackingRepo {

  import db.Driver.simple._

  type RepoImpl = LibraryViewTrackingTable

  implicit def sourceTypeMapper = MappedColumnType.base[LibraryViewSource, String](
    { source => source.value }, { value => LibraryViewSource(value) }
  )

  class LibraryViewTrackingTable(tag: Tag) extends RepoTable[LibraryViewTracking](db, tag, "library_view") {
    def ownerId = column[Id[User]]("owner_id", O.NotNull)
    def viewerId = column[Option[Id[User]]]("viewer_id", O.Nullable)
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def source = column[Option[LibraryViewSource]]("source", O.Nullable)
    def * = (id.?, createdAt, updatedAt, ownerId, viewerId, libraryId, source, state) <> ((LibraryViewTracking.apply _).tupled, LibraryViewTracking.unapply _)
  }

  def table(tag: Tag) = new LibraryViewTrackingTable(tag)
  initTable()

  override def invalidateCache(model: LibraryViewTracking)(implicit session: RSession): Unit = {}
  override def deleteCache(model: LibraryViewTracking)(implicit session: RSession): Unit = {}

  def getTotalViews(ownerId: Id[User], since: DateTime)(implicit session: RSession): Int = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select count(*) from library_view where owner_id = ${ownerId} and owner_id != viewer_id and source is not null and created_at > ${since}"
    q.as[Int].list.headOption.getOrElse(0)
  }

  def getTopViewedLibrariesAndCounts(ownerId: Id[User], since: DateTime, limit: Int)(implicit session: RSession): Map[Id[Library], Int] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val q = sql"select library_id, count(*) cnt from library_view where owner_id = ${ownerId} and owner_id != viewer_id and source is not null and created_at > ${since} group by library_id order by cnt desc limit ${limit}"
    q.as[(Int, Int)].list.map { case (id, cnt) => (Id[Library](id), cnt) }.toMap
  }

}
