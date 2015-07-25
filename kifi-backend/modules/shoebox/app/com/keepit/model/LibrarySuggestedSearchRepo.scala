package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibrarySuggestedSearchRepoImpl])
trait LibrarySuggestedSearchRepo extends DbRepo[LibrarySuggestedSearch] {
  def getSuggestedTermsByLibrary(libId: Id[Library], limit: Int, kind: SuggestedSearchTermKind)(implicit session: RSession): SuggestedSearchTerms
  def getByLibraryId(libId: Id[Library], kind: SuggestedSearchTermKind)(implicit session: RSession): Seq[LibrarySuggestedSearch]
}

@Singleton
class LibrarySuggestedSearchRepoImpl @Inject() (
    val db: DataBaseComponent,
    termsCache: LibrarySuggestedSearchCache,
    val clock: Clock,
    airbrake: AirbrakeNotifier) extends DbRepo[LibrarySuggestedSearch] with LibrarySuggestedSearchRepo {

  import db.Driver.simple._

  type RepoImpl = LibrarySuggestedSearchTable

  implicit val termKindMapper = MappedColumnType.base[SuggestedSearchTermKind, String](_.value, SuggestedSearchTermKind.apply)

  class LibrarySuggestedSearchTable(tag: Tag) extends RepoTable[LibrarySuggestedSearch](db, tag, "library_suggested_search") {
    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def term = column[String]("term", O.NotNull)
    def weight = column[Float]("weight", O.NotNull)
    def kind = column[SuggestedSearchTermKind]("kind", O.NotNull)
    def * = (id.?, createdAt, updatedAt, libraryId, term, weight, state, kind) <> ((LibrarySuggestedSearch.apply _).tupled, LibrarySuggestedSearch.unapply _)
  }

  def table(tag: Tag) = new LibrarySuggestedSearchTable(tag)
  initTable()

  def invalidateCache(model: LibrarySuggestedSearch)(implicit session: RSession): Unit = {
    // do not eagerly update. Each save operation acts on a term. What we cache is a collection of terms.
    deleteCache(model)
  }

  def deleteCache(model: LibrarySuggestedSearch)(implicit session: RSession): Unit = {
    termsCache.remove(LibrarySuggestedSearchKey(model.libraryId, SuggestedSearchTermKind.AUTO))
    termsCache.remove(LibrarySuggestedSearchKey(model.libraryId, SuggestedSearchTermKind.HASHTAG))
  }

  def getSuggestedTermsByLibrary(libId: Id[Library], limit: Int, kind: SuggestedSearchTermKind)(implicit session: RSession): SuggestedSearchTerms = {
    // first, ignore the parameter limit.
    val maxCached = termsCache.getOrElse(LibrarySuggestedSearchKey(libId, kind)) {
      val q = { for { r <- rows if r.libraryId === libId && r.state === LibrarySuggestedSearchStates.ACTIVE && r.kind === kind } yield r }.sortBy(_.weight.desc).take(SuggestedSearchTerms.MAX_CACHE_LIMIT).list
      val terms = q.map { m => (m.term, m.weight) }.toMap
      SuggestedSearchTerms(terms)
    }

    maxCached.takeTopK(limit)
  }

  def getByLibraryId(libId: Id[Library], kind: SuggestedSearchTermKind)(implicit session: RSession): Seq[LibrarySuggestedSearch] = {
    { for { r <- rows if r.libraryId === libId && r.kind === kind } yield r }.list
  }
}
