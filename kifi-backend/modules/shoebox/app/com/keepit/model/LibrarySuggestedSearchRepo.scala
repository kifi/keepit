package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibrarySuggestedSearchRepoImpl])
trait LibrarySuggestedSearchRepo extends DbRepo[LibrarySuggestedSearch] {
  def getSuggestedTermsByLibrary(libId: Id[Library], limit: Int)(implicit session: RSession): SuggestedSearchTerms
  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Seq[LibrarySuggestedSearch]
}

@Singleton
class LibrarySuggestedSearchRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[LibrarySuggestedSearch] with LibrarySuggestedSearchRepo {

  import db.Driver.simple._

  type RepoImpl = LibrarySuggestedSearchTable

  class LibrarySuggestedSearchTable(tag: Tag) extends RepoTable[LibrarySuggestedSearch](db, tag, "library_suggested_search") {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def term = column[String]("term", O.NotNull)
    def weight = column[Float]("weight", O.NotNull)
    def * = (id.?, createdAt, updatedAt, libraryId, term, weight, state) <> ((LibrarySuggestedSearch.apply _).tupled, LibrarySuggestedSearch.unapply _)
  }

  def table(tag: Tag) = new LibrarySuggestedSearchTable(tag)
  initTable()

  def invalidateCache(keep: LibrarySuggestedSearch)(implicit session: RSession): Unit = {}

  def deleteCache(uri: LibrarySuggestedSearch)(implicit session: RSession): Unit = {}

  def getSuggestedTermsByLibrary(libId: Id[Library], limit: Int)(implicit session: RSession): SuggestedSearchTerms = {
    val q = { for { r <- rows if r.libraryId === libId && r.state === LibrarySuggestedSearchStates.ACTIVE } yield r }.sortBy(_.weight.desc).take(limit).list
    val terms = q.map { m => (m.term, m.weight) }.toMap
    SuggestedSearchTerms(terms)
  }

  def getByLibraryId(libId: Id[Library])(implicit session: RSession): Seq[LibrarySuggestedSearch] = {
    { for { r <- rows if r.libraryId === libId } yield r }.list
  }
}
