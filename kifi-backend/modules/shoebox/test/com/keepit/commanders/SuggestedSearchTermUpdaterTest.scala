package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._

import com.keepit.queue.DevLibrarySuggestedSearchQueueModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

class SuggestedSearchTermUpdaterTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(DevLibrarySuggestedSearchQueueModule())

  def setup(implicit injector: Injector) = {
    val N = 10
    val keeps = KeepFactory.keeps(N)
    var hashtag = ""
    db.readWrite { implicit session =>
      LibraryFactory.library().withId(Id[Library](1)).saved

      (0 until N).map { i =>
        hashtag = Hashtags.addNewHashtagsToString(hashtag, Seq(Hashtag(s"h${i + 1}")))
        keeps(i).withNote(Some(hashtag)).withLibrary(Id[Library](1)).withUser(Id[User](1)).saved
      }
    }
  }

  "SuggestedSearchTermUpdater" should {
    "sync with keepRepo and send autotag requests to sqs queue" in {
      withDb(modules: _*) { implicit injector =>

        val updater = inject[SuggestedSearchTermUpdater]
        updater.update() === 0

        setup(injector)
        updater.update() === 10

        val termCommander = inject[LibrarySuggestedSearchCommander]
        val terms = termCommander.getSuggestedTermsForLibrary(Id[Library](1), 10, SuggestedSearchTermKind.HASHTAG)
        terms.terms.toArray.sortBy(-_._2).toList === (1 to 10).map { i => (s"h${i}", (11 - i) * 1f) }.toList

      }
    }
  }

}
