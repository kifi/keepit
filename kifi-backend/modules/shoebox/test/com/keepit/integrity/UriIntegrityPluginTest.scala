package com.keepit.integrity

import com.google.inject.Injector
import com.keepit.common.time.FakeClock
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike
import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import scala.util.Random

class UriIntegrityPluginTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(FakeActorSystemModule())

  private def migrateUriPlease(oldUriId: Id[NormalizedURI], newUriId: Id[NormalizedURI])(implicit injector: Injector): Unit = {
    db.readWrite { implicit session =>
      inject[ChangedURIRepo].save(ChangedURI(oldUriId = oldUriId, newUriId = newUriId))
    }
  }

  "uri integrity plugin" should {
    "work" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val bmRepo = inject[KeepRepo]
        val seqAssigner = inject[ChangedURISeqAssigner]
        val plugin = inject[UriIntegrityPlugin]
        plugin.onStart()

        def setup() = {
          db.readWrite { implicit session =>
            val nuri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com", Some("Google")).withState(NormalizedURIStates.SCRAPED))
            val nuri1 = uriRepo.save(NormalizedURI.withHash("http://google.com", Some("Google")))
            val nuri2 = uriRepo.save(NormalizedURI.withHash("http://www.bing.com", Some("Bing")).withState(NormalizedURIStates.SCRAPED))
            val nuri3 = uriRepo.save(NormalizedURI.withHash("http://www.fakebing.com", Some("Bing")))

            val user = UserFactory.user().withName("foo", "bar").withUsername("test").saved
            val user2 = UserFactory.user().withName("abc", "xyz").withUsername("test").saved

            val main = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("asdf"), memberCount = 1))

            val hover = KeepSource.Keeper
            val bm1 = KeepFactory.keep().withUser(user).withUri(nuri0).withLibrary(main).saved
            val bm2 = KeepFactory.keep().withUser(user).withUri(nuri2).withLibrary(main).saved
            val bm3 = KeepFactory.keep().withUser(user2).withUri(nuri2).withLibrary(main).saved

            (Array(nuri0, nuri1, nuri2, nuri3), Array(bm1, bm2, bm3))
          }
        }

        val (uris, bms) = setup()

        // check init status
        db.readOnlyMaster { implicit s =>
          uriRepo.getByState(NormalizedURIStates.ACTIVE, -1).size === 2
          uriRepo.getByState(NormalizedURIStates.SCRAPED, -1).size === 2
        }

        // merge
        migrateUriPlease(uris(0).id.get, uris(1).id.get)
        seqAssigner.assignSequenceNumbers()
        plugin.batchURIMigration()

        // check redirection
        db.readOnlyMaster { implicit s =>
          uriRepo.getByState(NormalizedURIStates.REDIRECTED, -1).size === 1
        }

        val centralConfig = inject[CentralConfig]
        centralConfig(URIMigrationSeqNumKey) === Some(SequenceNumber[ChangedURI](1))
      }
    }

  }
}
