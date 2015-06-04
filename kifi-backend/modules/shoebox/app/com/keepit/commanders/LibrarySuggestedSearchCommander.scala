package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.model._

@ImplementedBy(classOf[LibrarySuggestedSearchCommanderImpl])
trait LibrarySuggestedSearchCommander {
  def getSuggestedTermsForLibrary(libId: Id[Library], limit: Int, kind: SuggestedSearchTermKind): SuggestedSearchTerms
  def saveSuggestedSearchTermsForLibrary(libId: Id[Library], terms: SuggestedSearchTerms, kind: SuggestedSearchTermKind): Unit
  def aggregateHashtagsForLibrary(libId: Id[Library], maxKeeps: Int, maxTerms: Int): SuggestedSearchTerms
}

@Singleton
class LibrarySuggestedSearchCommanderImpl @Inject() (
    val db: Database,
    val keepRepo: KeepRepo,
    val suggestedSearchRepo: LibrarySuggestedSearchRepo) extends LibrarySuggestedSearchCommander {

  def getSuggestedTermsForLibrary(libId: Id[Library], limit: Int, kind: SuggestedSearchTermKind): SuggestedSearchTerms = {
    db.readOnlyReplica { implicit s => suggestedSearchRepo.getSuggestedTermsByLibrary(libId, limit, kind) }
  }

  // overwrite existing, create new, deactivate the rest
  def saveSuggestedSearchTermsForLibrary(libId: Id[Library], terms: SuggestedSearchTerms, kind: SuggestedSearchTermKind): Unit = {
    db.readWrite { implicit s =>
      val current = suggestedSearchRepo.getByLibraryId(libId, kind)
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
        val m = LibrarySuggestedSearch(term = term, weight = normTerms.terms(term), libraryId = libId, termKind = kind)
        suggestedSearchRepo.save(m)
      }
    }
  }

  def aggregateHashtagsForLibrary(libId: Id[Library], maxKeeps: Int, maxTerms: Int): SuggestedSearchTerms = {
    val notes = db.readOnlyReplica { implicit s =>
      keepRepo.recentKeepNotes(libId, limit = maxKeeps)
    }
    val mapped: Seq[(String, Int)] = notes.map { note => Hashtags.findAllHashtagNames(note).map { tag => (tag.toLowerCase, 1) } }.flatten.filter(!_._1.startsWith("imported from"))
    val reduced = mapped.groupBy(_._1).map { case (tag, cnts) => tag -> 1f * cnts.size }.toArray.sortBy(-_._2).take(maxTerms).toMap
    SuggestedSearchTerms(reduced)
  }
}
