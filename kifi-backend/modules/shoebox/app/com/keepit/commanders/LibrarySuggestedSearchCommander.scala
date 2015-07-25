package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.model._

import scala.collection.mutable

@ImplementedBy(classOf[LibrarySuggestedSearchCommanderImpl])
trait LibrarySuggestedSearchCommander {
  def getSuggestedTermsForLibrary(libId: Id[Library], limit: Int, kind: SuggestedSearchTermKind): SuggestedSearchTerms
  def saveSuggestedSearchTermsForLibrary(libId: Id[Library], terms: SuggestedSearchTerms, kind: SuggestedSearchTermKind): Unit
  def aggregateHashtagsForLibrary(libId: Id[Library], maxKeeps: Int, maxTerms: Int): SuggestedSearchTerms
  def getTopTermsForLibrary(libId: Id[Library], limit: Int): (Array[String], Array[Float])
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
    val reduced = mapped.groupBy(_._1).map { case (tag, cnts) => ("#" + tag) -> 1f * cnts.size }.filter(_._2 > 1f).toArray.sortBy(-_._2).take(maxTerms).toMap
    SuggestedSearchTerms(reduced)
  }

  def getTopTermsForLibrary(libId: Id[Library], limit: Int): (Array[String], Array[Float]) = {
    val LIMIT = limit

    def similar(s1: String, s2: String): Boolean = {
      val ts1 = s1.split(" ").flatMap(_.sliding(2)).toSet
      val ts2 = s2.split(" ").flatMap(_.sliding(2)).toSet
      ts1.intersect(ts2).size * 1.0 / ts1.union(ts2).size > 0.6
    }

    // 1. get user hashtags
    val topHashtags = getSuggestedTermsForLibrary(libId, limit = LIMIT, kind = SuggestedSearchTermKind.HASHTAG)
    val (hashTerms, hashWeights) = topHashtags.terms.toArray.sortBy(-_._2).unzip

    // 2. get auto generated tags, make sure no near duplication.
    val topAuto = getSuggestedTermsForLibrary(libId, limit = LIMIT * 2, kind = SuggestedSearchTermKind.AUTO)
    val (autoTerms, autoWeights): (Array[String], Array[Float]) = if (topAuto.terms.size < LIMIT) (Array[String](), Array[Float]()) else {
      val (tms, ws) = topAuto.terms.toArray.sortBy(-_._2).unzip
      val taken = mutable.Set[String]()
      tms.foreach { tm =>
        val dup = taken.find(x => similar(tm, x))
        if (dup.isEmpty) taken.add(tm)
      }

      val (tms2, ws2) = (tms zip ws).filter { case (word, weight) => taken.contains(word) }.take(LIMIT).unzip
      (tms2.toArray, ws2.toArray)
    }

    // 3. "combine". this could be tuned later
    if (autoTerms.size == 0) (hashTerms.toArray, hashWeights.toArray)
    else (autoTerms, autoWeights)
  }
}
