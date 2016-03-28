package com.keepit.integrity

import com.google.inject.Module
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.db.slick._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model._
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.common.core._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactory

class OrphanCleanerTest extends Specification with ShoeboxTestInjector {

  val modules: Seq[Module] = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeKeepImportsModule()
  )

  @inline def assignSeqNums(db: Database)(repos: SeqNumberFunction[_]*): Unit = {
    db.readWrite { implicit rw =>
      repos foreach { repo =>
        repo.assignSequenceNumbers(1000)
      }
    }
  }

  "OphanCleaner" should {

    "clean up uris by changed uris" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val keepRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, lib1) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("foo", "bar").withUsername("test").saved
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withContentRequest(true))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withContentRequest(true))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withContentRequest(true))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withContentRequest(true))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withContentRequest(true))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          uriRepo.assignSequenceNumbers(1000)

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val bms = db.readWrite { implicit session =>
          val bm0 = KeepFactory.keep().withTitle("google").withUser(user).withUri(uris(0)).withLibrary(lib1).saved
          val bm1 = KeepFactory.keep().withTitle("bing").withUser(user).withUri(uris(1)).withLibrary(lib1).saved
          val bm2 = KeepFactory.keep().withTitle("yahoo").withUser(user).withUri(uris(2)).withLibrary(lib1).saved
          val bm3 = KeepFactory.keep().withTitle("ask").withUser(user).withUri(uris(3)).withLibrary(lib1).saved

          Seq(bm0, bm1, bm2, bm3)
        }

        // initial state
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).shouldHaveContent === true
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).shouldHaveContent === true
          uriRepo.get(uris(5).id.get).shouldHaveContent === true
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }

        db.readWrite { implicit session =>
          uris.drop(1).map { uri => changedURIRepo.save(ChangedURI(oldUriId = uri.id.get, newUriId = uris(0).id.get, state = ChangedURIStates.APPLIED)) }
        }
        val seqAssigner = inject[ChangedURISeqAssigner]
        seqAssigner.assignSequenceNumbers()
        cleaner.cleanNormalizedURIsByChangedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).shouldHaveContent === true
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }
      }
    }

    "clean up uris by bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val keepRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        @inline def doAssign[T](f: => T): T = {
          f tap { _ => assignSeqNums(db)(uriRepo, keepRepo) }
        }

        val (user, other, lib1) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("foo", "bar").withUsername("test").saved
          val other = UserFactory.user().withName("foo", "bar").withUsername("test").saved
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, other, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withContentRequest(true))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withContentRequest(true))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withState(NormalizedURIStates.ACTIVE))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withState(NormalizedURIStates.REDIRECTED))

          uriRepo.assignSequenceNumbers(1000)

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4)
        }
        var bms = doAssign {
          db.readWrite { implicit session =>
            val bm0 = KeepFactory.keep().withTitle("google").withUser(user).withUri(uris(0)).withLibrary(lib1).saved
            val bm1 = KeepFactory.keep().withTitle("bing").withUser(user).withUri(uris(1)).withLibrary(lib1).saved
            Seq(bm0, bm1)
          }
        }

        // initial states
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        bms ++= doAssign {
          db.readWrite { implicit session =>
            uriRepo.save(uris(0).withContentRequest(true))
            uriRepo.save(uris(1).withContentRequest(true))

            uriRepo.assignSequenceNumbers(1000)

            Seq(KeepFactory.keep().withTitle("Yahoo").withUri(uris(2)).withUser(user).withLibrary(lib1).saved)
          }
        }
        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        bms ++= doAssign {
          db.readWrite { implicit session =>
            Seq(KeepFactory.keep().withTitle("AltaVista").withUser(user).withUri(uris(3)).withLibrary(lib1).saved)
          }
        }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.INACTIVE
        }
        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        // test: to ACTIVE
        doAssign {
          db.readWrite { implicit session =>
            bms.foreach { bm => keepRepo.save(bm.copy(state = KeepStates.INACTIVE)) }
          }
        }
        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        // test: to ACTIVE (first two uris are kept by other)
        val obms = doAssign {
          db.readWrite { implicit s =>
            uriRepo.save(uris(0).withContentRequest(true))
            uriRepo.save(uris(1).withContentRequest(true))
            Seq(
              KeepFactory.keep().withTitle("google").withUser(other).withUri(uris(0)).withLibrary(lib1).saved,
              KeepFactory.keep().withTitle("bing").withUser(other).withUri(uris(1)).withLibrary(lib1).saved
            )
          }
        }

        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }

        // test: sequence of changes
        doAssign {
          db.readWrite { implicit session =>
            keepRepo.save(bms(0).copy(state = KeepStates.ACTIVE))
            keepRepo.save(bms(1).copy(state = KeepStates.ACTIVE))
            keepRepo.save(obms(0).copy(state = KeepStates.INACTIVE))
          }
        }
        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }
        doAssign {
          db.readWrite { implicit session =>
            keepRepo.save(bms(0).copy(state = KeepStates.INACTIVE))
            keepRepo.save(bms(1).copy(state = KeepStates.INACTIVE))
            keepRepo.save(obms(0).copy(state = KeepStates.ACTIVE))
            keepRepo.save(obms(1).copy(state = KeepStates.INACTIVE))
          }
        }
        doAssign { cleaner.clean(readOnly = false) }
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(2).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.REDIRECTED
        }
      }
    }

    "clean up uris by normalized uris" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val keepRepo = inject[KeepRepo]
        val cleaner = inject[OrphanCleaner]

        val (user, lib1) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("foo", "bar").withUsername("test").saved
          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          (user, lib1)
        }

        val hover = KeepSource.keeper

        val uris = db.readWrite { implicit session =>
          val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")).withContentRequest(true))
          val nuri1 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com/", Some("Bing")).withContentRequest(true))
          val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.yahooo.com/", Some("Yahoo")).withContentRequest(true))
          val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.ask.com/", Some("Ask")).withState(NormalizedURIStates.ACTIVE))
          val nuri4 = uriRepo.save(NormalizedURI.withHash("http://www.inktomi.com/", Some("Inktomi")).withContentRequest(true))
          val nuri5 = uriRepo.save(NormalizedURI.withHash("http://www.lycos.com/", Some("Lycos")).withContentRequest(true))
          val nuri6 = uriRepo.save(NormalizedURI.withHash("http://www.infoseek.com/", Some("Infoseek")).withState(NormalizedURIStates.ACTIVE))
          val nuri7 = uriRepo.save(NormalizedURI.withHash("http://www.altavista.com/", Some("AltaVista")).withState(NormalizedURIStates.INACTIVE))

          uriRepo.assignSequenceNumbers(1000)

          Seq(nuri0, nuri1, nuri2, nuri3, nuri4, nuri5, nuri6, nuri7)
        }
        val bms = db.readWrite { implicit session =>
          Seq(
            KeepFactory.keep().withTitle("google").withUser(user).withUri(uris(0)).withLibrary(lib1).saved,
            KeepFactory.keep().withTitle("bing").withUser(user).withUri(uris(1)).withLibrary(lib1).saved,
            KeepFactory.keep().withTitle("yahoo").withUser(user).withUri(uris(2)).withLibrary(lib1).saved,
            KeepFactory.keep().withTitle("ask").withUser(user).withUri(uris(3)).withLibrary(lib1).saved
          )
        }

        // initial state
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).shouldHaveContent === true
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).shouldHaveContent === true
          uriRepo.get(uris(5).id.get).shouldHaveContent === true
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }

        cleaner.cleanNormalizedURIsByNormalizedURIs(readOnly = false)
        db.readOnlyMaster { implicit s =>
          uriRepo.get(uris(0).id.get).shouldHaveContent === true
          uriRepo.get(uris(1).id.get).shouldHaveContent === true
          uriRepo.get(uris(2).id.get).shouldHaveContent === true
          uriRepo.get(uris(3).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(4).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(5).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(6).id.get).state === NormalizedURIStates.ACTIVE
          uriRepo.get(uris(7).id.get).state === NormalizedURIStates.INACTIVE
        }
      }
    }
  }
}
