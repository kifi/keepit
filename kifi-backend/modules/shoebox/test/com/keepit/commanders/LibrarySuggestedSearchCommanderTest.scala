package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class LibrarySuggestedSearchCommanderTest extends Specification with ShoeboxTestInjector {

  def setup(implicit injector: Injector) {
    val repo = inject[LibrarySuggestedSearchRepo]

    val t1 = LibrarySuggestedSearch(term = "a", weight = 1f, libraryId = Id[Library](1), termKind = SuggestedSearchTermKind.AUTO)
    val t2 = t1.copy(term = "b", weight = 2f)
    val t3 = t1.copy(term = "c", weight = 3f, state = LibrarySuggestedSearchStates.INACTIVE)
    val t4 = t1.copy(term = "d", weight = 4f, state = LibrarySuggestedSearchStates.INACTIVE)

    db.readWrite { implicit s =>
      List(t1, t2, t3, t4).foreach { t =>
        repo.save(t)
      }
    }
  }

  "LibrarySuggestedSearchCommander" should {
    val kind = SuggestedSearchTermKind.AUTO

    "retrieve suggested terms in library" in {
      withDb() { implicit injector =>
        setup(injector)
        val commander = inject[LibrarySuggestedSearchCommander]
        val id = Id[Library](1)
        var ts = commander.getSuggestedTermsForLibrary(id, limit = 1, kind)
        ts.terms === Map("b" -> 2f)

        ts = commander.getSuggestedTermsForLibrary(id, limit = 2, kind)
        ts.terms === Map("b" -> 2f, "a" -> 1f)

        ts = commander.getSuggestedTermsForLibrary(id, limit = 3, kind)
        ts.terms === Map("b" -> 2f, "a" -> 1f)
      }
    }

    "save new terms" in {
      withDb() { implicit injector =>
        setup(injector)

        val commander = inject[LibrarySuggestedSearchCommander]
        val newTerms = Map("A" -> 1.5f, "C" -> 3.5f, "E" -> 5f)
        val id = Id[Library](1)
        commander.saveSuggestedSearchTermsForLibrary(id, SuggestedSearchTerms(newTerms), SuggestedSearchTermKind.AUTO)
        var ts = commander.getSuggestedTermsForLibrary(id, limit = 1, kind)
        ts.terms === Map("e" -> 5f)

        ts = commander.getSuggestedTermsForLibrary(id, limit = 2, kind)
        ts.terms === Map("e" -> 5f, "c" -> 3.5f)

        ts = commander.getSuggestedTermsForLibrary(id, limit = 3, kind)
        ts.terms === Map("e" -> 5f, "c" -> 3.5f, "a" -> 1.5f)

        ts = commander.getSuggestedTermsForLibrary(id, limit = 4, kind)
        ts.terms === Map("e" -> 5f, "c" -> 3.5f, "a" -> 1.5f)

      }
    }
  }

}
