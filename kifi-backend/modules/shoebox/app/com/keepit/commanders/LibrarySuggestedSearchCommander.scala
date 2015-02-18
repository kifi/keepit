package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._

@ImplementedBy(classOf[LibrarySuggestedSearchCommanderImpl])
trait LibrarySuggestedSearchCommander {
  def getSuggestedTermsForLibrary(libId: Id[Library], limit: Int): SuggestedSearchTerms
  def saveSuggestedSearchTermsForLibrary(libId: Id[Library], terms: SuggestedSearchTerms): Unit
}

@Singleton
class LibrarySuggestedSearchCommanderImpl @Inject() (
    val db: Database,
    val suggestedSearchRepo: LibrarySuggestedSearchRepo) extends LibrarySuggestedSearchCommander {

  def getSuggestedTermsForLibrary(libId: Id[Library], limit: Int): SuggestedSearchTerms = {
    db.readOnlyReplica { implicit s => suggestedSearchRepo.getSuggestedTermsByLibrary(libId, limit) }
  }

  // overwrite existing, create new, deactivate the rest
  def saveSuggestedSearchTermsForLibrary(libId: Id[Library], terms: SuggestedSearchTerms): Unit = {
    db.readWrite { implicit s =>
      val current = suggestedSearchRepo.getByLibraryId(libId)
      val currentMap = current.groupBy(_.term).map { case (term, models) => (term, models.head) }
      val normTerms = terms.normalized()
      val (overwrite, deactivate, create) = (currentMap.keySet & normTerms.terms.keySet, currentMap.keySet -- normTerms.terms.keySet, normTerms.terms.keySet -- currentMap.keySet)

      overwrite.foreach { term =>
        val toSave = currentMap(term).activateWithWeight(normTerms.terms(term))
        suggestedSearchRepo.save(toSave)
      }

      deactivate.foreach { term =>
        val m = currentMap(term)
        if (m.state == LibrarySuggestedSearchStates.ACTIVE) {
          suggestedSearchRepo.save(m.copy(state = LibrarySuggestedSearchStates.INACTIVE))
        }
      }

      create.foreach { term =>
        val m = LibrarySuggestedSearch(term = term, weight = normTerms.terms(term), libraryId = libId)
        suggestedSearchRepo.save(m)
      }
    }
  }
}
