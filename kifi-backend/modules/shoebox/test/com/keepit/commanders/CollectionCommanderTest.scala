package com.keepit.commanders

import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.heimdal.HeimdalContext
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.scraper.FakeScrapeSchedulerModule
import scala.Some
import com.keepit.model.KeepToCollection
import com.keepit.normalizer.NormalizationService
import com.keepit.search.FakeSearchServiceClientModule

class CollectionCommanderTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty

  def modules = FakeScrapeSchedulerModule() :: FakeSearchServiceClientModule() :: Nil

  "CollectionCommander" should {

    "remove tags and tags to keeps" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val keeper = BookmarkSource.keeper

        val (user, collections, bookmark1, bookmark2) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
          val normalizationService = inject[NormalizationService]
          val uri1 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val bookmark1 = bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = BookmarkStates.ACTIVE))
          val bookmark2 = bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = BookmarkStates.ACTIVE))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
            Nil
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = collections(1).id.get))
          (user1, collections, bookmark1, bookmark2)
        }

        db.readOnly { implicit s =>
          val tagId = collections(0).id.get
          collectionRepo.get(tagId).state.value === "active"
          val bookmarksWithTags = bookmarkRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
          bookmarksWithTags.size === 2
          (bookmarksWithTags map {b => b.id.get}).toSet === Set(bookmark1.id.get, bookmark2.id.get)
        }

        db.readOnly { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "active"
          val bookmarksWithTags = bookmarkRepo.getByUserAndCollection(user.id.get, collections(1).id.get, None, None, 1000)
          bookmarksWithTags.size === 1
          bookmarksWithTags.head.id.get === bookmark1.id.get
        }

        db.readOnly { implicit s =>
          collectionRepo.get(collections(2).id.get).state.value === "active"
//          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(2).id.get), 1000) === 0
        }

        inject[CollectionCommander].deleteCollection(collections(0))

        db.readOnly { implicit s =>
          collectionRepo.get(collections(0).id.get).state.value === "inactive"
//          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000) === 0
        }

        db.readOnly { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "active"
//          val bookmarksWithTags = bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(1).id.get), 1000)
//          bookmarksWithTags.size === 1
//          bookmarksWithTags.head.id.get === bookmark1.id.get
        }

        inject[CollectionCommander].deleteCollection(collections(1))

        db.readOnly { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "inactive"
//          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000) === 0
        }

        db.readOnly { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "inactive"
//          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(1).id.get), 1000) === 0
        }

        inject[CollectionCommander].deleteCollection(collections(2))

        db.readOnly { implicit s =>
          collectionRepo.get(collections(2).id.get).state.value === "inactive"
//          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(2).id.get), 1000) === 0
        }
      }
    }
  }
}
