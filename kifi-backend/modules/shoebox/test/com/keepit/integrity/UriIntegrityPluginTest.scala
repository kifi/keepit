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

            val hover = KeepSource.keeper
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

    "handle collections correctly when migrating bookmarks" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val uriRepo = inject[NormalizedURIRepo]
        val collectionRepo = inject[CollectionRepo]
        val keepToCollectionRepo = inject[KeepToCollectionRepo]
        val bmRepo = inject[KeepRepo]
        val seqAssigner = inject[ChangedURISeqAssigner]
        val plugin = inject[UriIntegrityPlugin]
        plugin.onStart()

        def setup() = {
          db.readWrite { implicit session =>

            /*
             * one user. 3 urls. each url has two versions of normalized uri, one of the two is better.
             *
             * keepToCollections:
             *  (c0, bm0), going to be inactive
             *  (c0, bm0better), will be untouched,
             *  (c0, b1), will be inactive,
             *  (c0, b2), will be inactive
             *  (c0, b2better, inactive), will be active
             *  (c1, b1better), will be untouched
             *  (c2, b2better), will be untouched,
             *  (c0, b1better) will be created
             *
             * */

            val user = UserFactory.user().withName("foo", "bar").withUsername("test").saved

            val uri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com", Some("Google")))
            val uri0better = uriRepo.save(NormalizedURI.withHash("http://google.com", Some("Google")))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/drive", Some("Google")))
            val uri1better = uriRepo.save(NormalizedURI.withHash("http://google.com/drive", Some("Google")))

            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/mail", Some("Google")))
            val uri2better = uriRepo.save(NormalizedURI.withHash("http://google.com/mail", Some("Google")))

            val main = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("asdf"), memberCount = 1))

            val bm0 = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri0).withLibrary(main).saved
            val bm0better = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri0better).withLibrary(main).saved
            val bm1 = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri1).withLibrary(main).saved
            val bm1better = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri1better).withLibrary(main).saved
            val bm2 = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri2).withLibrary(main).saved
            val bm2better = KeepFactory.keep().withTitle("google").withUser(user).withUri(uri2better).withLibrary(main).saved

            val c0 = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("google")))
            val c1 = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("googleBetter")))

            keepToCollectionRepo.save(KeepToCollection(keepId = bm0.id.get, collectionId = c0.id.get))
            keepToCollectionRepo.save(KeepToCollection(keepId = bm0better.id.get, collectionId = c0.id.get))

            keepToCollectionRepo.save(KeepToCollection(keepId = bm1.id.get, collectionId = c0.id.get))

            keepToCollectionRepo.save(KeepToCollection(keepId = bm2.id.get, collectionId = c0.id.get))
            keepToCollectionRepo.save(KeepToCollection(keepId = bm2better.id.get, collectionId = c0.id.get, state = KeepToCollectionStates.INACTIVE))

            keepToCollectionRepo.save(KeepToCollection(keepId = bm1better.id.get, collectionId = c1.id.get))
            keepToCollectionRepo.save(KeepToCollection(keepId = bm2better.id.get, collectionId = c1.id.get))

            collectionRepo.collectionChanged(c0.id.get, true, false)
            collectionRepo.collectionChanged(c1.id.get, true, false)

            (Array(uri0, uri1, uri2), Array(uri0better, uri1better, uri2better), Array(bm0, bm1, bm2), Array(bm0better, bm1better, bm2better))
          }
        }

        val (uris, betterUris, bms, betterBms) = setup()

        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getByKeep(bms(0).id.get).size === 1
          keepToCollectionRepo.getByKeep(bms(1).id.get).size === 1
          keepToCollectionRepo.getByKeep(bms(2).id.get).size === 1

          keepToCollectionRepo.getByKeep(betterBms(0).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(1).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(2).id.get).size === 1
        }

        migrateUriPlease(uris(0).id.get, betterUris(0).id.get)
        migrateUriPlease(uris(1).id.get, betterUris(1).id.get)
        migrateUriPlease(uris(2).id.get, betterUris(2).id.get)
        seqAssigner.assignSequenceNumbers()
        plugin.batchURIMigration()

        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getByKeep(bms(0).id.get).size === 1
          keepToCollectionRepo.getByKeep(bms(1).id.get).size === 1
          keepToCollectionRepo.getByKeep(bms(2).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(0).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(1).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(2).id.get).size === 1
        }

      }
    }
  }
}
