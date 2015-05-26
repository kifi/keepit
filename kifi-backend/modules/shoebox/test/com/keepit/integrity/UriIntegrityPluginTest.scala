package com.keepit.integrity

import org.specs2.mutable.SpecificationLike
import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.SequenceNumber
import com.keepit.common.zookeeper.CentralConfig

class UriIntegrityPluginTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(FakeActorSystemModule())

  "uri integrity plugin" should {
    "work" in {
      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
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

            val url0 = urlRepo.save(URLFactory("http://www.google.com/", nuri0.id.get)) // to be redirected to nuri1
            val url1 = urlRepo.save(URLFactory("http://www.bing.com/", nuri2.id.get))
            val url2 = urlRepo.save(URLFactory("http://www.fakebing.com/", nuri2.id.get)) // to be splitted, to be pointing to

            val user = userRepo.save(User(firstName = "foo", lastName = "bar", username = Username("test"), normalizedUsername = "test"))
            val user2 = userRepo.save(User(firstName = "abc", lastName = "xyz", username = Username("test"), normalizedUsername = "test"))

            val main = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("asdf"), memberCount = 1))

            val hover = KeepSource.keeper
            val bm1 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url0.url, urlId = url0.id.get,
              uriId = nuri0.id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))
            val bm2 = bmRepo.save(Keep(title = Some("bing"), userId = user.id.get, url = url1.url, urlId = url1.id.get,
              uriId = nuri2.id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))
            val bm3 = bmRepo.save(Keep(title = Some("bing"), userId = user2.id.get, url = url2.url, urlId = url2.id.get,
              uriId = nuri2.id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))

            (Array(nuri0, nuri1, nuri2, nuri3), Array(url0, url1, url2), Array(bm1, bm2, bm3))
          }
        }

        val (uris, urls, bms) = setup()

        // check init status
        db.readOnlyMaster { implicit s =>
          uriRepo.getByState(NormalizedURIStates.ACTIVE, -1).size === 2
          uriRepo.getByState(NormalizedURIStates.SCRAPED, -1).size === 2

          urlRepo.getByNormUri(uris(0).id.get).head.url === urls(0).url
          urlRepo.getByNormUri(uris(1).id.get) === Nil

          urlRepo.getByNormUri(uris(2).id.get).size === 2
          urlRepo.getByNormUri(uris(3).id.get).size === 0

          bmRepo.getByUrlId(urls(0).id.get).head.uriId === uris(0).id.get

        }

        // merge
        plugin.handleChangedUri(URIMigration(uris(0).id.get, uris(1).id.get))
        seqAssigner.assignSequenceNumbers()
        plugin.batchURIMigration()

        // check redirection
        db.readOnlyMaster { implicit s =>
          uriRepo.getByState(NormalizedURIStates.REDIRECTED, -1).size === 1

          urlRepo.getByNormUri(uris(1).id.get).head.url === urls(0).url
          urlRepo.getByNormUri(uris(0).id.get) === Nil

          bmRepo.getByUrlId(urls(0).id.get).head.uriId === uris(1).id.get
          bmRepo.getByUrlId(urls(1).id.get).head.uriId === uris(2).id.get
          bmRepo.getByUrlId(urls(2).id.get).head.uriId === uris(2).id.get

        }

        val centralConfig = inject[CentralConfig]
        centralConfig(URIMigrationSeqNumKey) === Some(SequenceNumber[ChangedURI](1))

        // split

        plugin.handleChangedUri(URLMigration(urls(2), uris(3).id.get))

        db.readOnlyMaster { implicit s =>
          uriRepo.getByState(NormalizedURIStates.REDIRECTED, -1).size === 1
          urlRepo.getByNormUri(uris(2).id.get).head.url === urls(1).url
          urlRepo.getByNormUri(uris(3).id.get).head.url === urls(2).url

          bmRepo.getByUrlId(urls(1).id.get).head.uriId === uris(2).id.get
          bmRepo.getByUrlId(urls(2).id.get).head.uriId === uris(3).id.get

        }

      }
    }

    "handle collections correctly when migrating bookmarks" in {

      withDb(modules: _*) { implicit injector =>
        val db = inject[Database]
        val urlRepo = inject[URLRepo]
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

            val user = userRepo.save(User(firstName = "foo", lastName = "bar", username = Username("test"), normalizedUsername = "test"))

            val uri0 = uriRepo.save(NormalizedURI.withHash("http://www.google.com", Some("Google")))
            val uri0better = uriRepo.save(NormalizedURI.withHash("http://google.com", Some("Google")))

            val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/drive", Some("Google")))
            val uri1better = uriRepo.save(NormalizedURI.withHash("http://google.com/drive", Some("Google")))

            val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/mail", Some("Google")))
            val uri2better = uriRepo.save(NormalizedURI.withHash("http://google.com/mail", Some("Google")))

            val url0 = urlRepo.save(URLFactory("http://www.google.com/", uri0.id.get))
            val url1 = urlRepo.save(URLFactory("http://www.google.com/drive", uri1.id.get))
            val url2 = urlRepo.save(URLFactory("http://www.google.com/mail", uri2.id.get))

            val main = libraryRepo.save(Library(name = "Lib", ownerId = user.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.SYSTEM_MAIN, slug = LibrarySlug("asdf"), memberCount = 1))

            val hover = KeepSource.keeper
            val bm0 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url0.url, urlId = url0.id.get, uriId = uri0.id.get, source = hover,
              visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))
            val bm0better = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url0.url, urlId = url0.id.get, uriId = uri0better.id.get, source = hover,
              visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))

            val bm1 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url1.url, urlId = url1.id.get,
              uriId = uri1.id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))
            val bm1better = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url1.url, urlId = url1.id.get, uriId = uri1better.id.get, source = hover,
              visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))

            val bm2 = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url2.url, urlId = url2.id.get,
              uriId = uri2.id.get, source = hover, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))
            val bm2better = bmRepo.save(Keep(title = Some("google"), userId = user.id.get, url = url2.url, urlId = url2.id.get, uriId = uri2better.id.get, source = hover,
              visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(main.id.get), inDisjointLib = main.isDisjoint))

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

        plugin.handleChangedUri(URIMigration(uris(0).id.get, betterUris(0).id.get))
        plugin.handleChangedUri(URIMigration(uris(1).id.get, betterUris(1).id.get))
        plugin.handleChangedUri(URIMigration(uris(2).id.get, betterUris(2).id.get))
        seqAssigner.assignSequenceNumbers()
        plugin.batchURIMigration()

        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getByKeep(bms(0).id.get).size === 0
          keepToCollectionRepo.getByKeep(bms(1).id.get).size === 0
          keepToCollectionRepo.getByKeep(bms(2).id.get).size === 0
          keepToCollectionRepo.getByKeep(betterBms(0).id.get).size === 1
          keepToCollectionRepo.getByKeep(betterBms(1).id.get).size === 2
          keepToCollectionRepo.getByKeep(betterBms(2).id.get).size === 2
        }

      }

    }
  }
}
