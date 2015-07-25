package com.keepit.commanders

import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.heimdal.HeimdalContext
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json.Json
import scala.Some
import com.keepit.model.KeepToCollection
import com.keepit.normalizer.NormalizationService
import com.keepit.search.FakeSearchServiceClientModule
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession

class CollectionCommanderTest extends Specification with ShoeboxTestInjector {

  implicit val context = HeimdalContext.empty

  def modules = FakeExecutionContextModule() :: FakeSearchServiceClientModule() :: Nil

  def prenormalize(url: String)(implicit injector: Injector): String = inject[NormalizationService].prenormalize(url).get

  "CollectionCommander" should {

    "remove tags and tags to keeps" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val keeper = KeepSource.keeper

        val (user, collections, bookmark1, bookmark2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().saved
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = library().saved

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction3"))) ::
            Nil
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = collections(0).id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(1).id.get))
          (user1, collections, bookmark1, bookmark2)
        }

        db.readOnlyMaster { implicit s =>
          val tagId = collections(0).id.get
          collectionRepo.get(tagId).state.value === "active"
          val bookmarksWithTags = keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
          bookmarksWithTags.size === 2
          (bookmarksWithTags map { b => b.id.get }).toSet === Set(bookmark1.id.get, bookmark2.id.get)
        }

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "active"
          val bookmarksWithTags = keepRepo.getByUserAndCollection(user.id.get, collections(1).id.get, None, None, 1000)
          bookmarksWithTags.size === 1
          bookmarksWithTags.head.id.get === bookmark1.id.get
        }

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(2).id.get).state.value === "active"
          //          keepRepo.getByUser(user.id.get, None, None, Some(collections(2).id.get), 1000) === 0
        }

        inject[CollectionCommander].deleteCollection(collections(0))

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(0).id.get).state.value === "inactive"
          //          keepRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000) === 0
        }

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "active"
          //          val bookmarksWithTags = keepRepo.getByUser(user.id.get, None, None, Some(collections(1).id.get), 1000)
          //          bookmarksWithTags.size === 1
          //          bookmarksWithTags.head.id.get === bookmark1.id.get
        }

        inject[CollectionCommander].deleteCollection(collections(1))

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "inactive"
          //          keepRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000) === 0
        }

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(1).id.get).state.value === "inactive"
          //          keepRepo.getByUser(user.id.get, None, None, Some(collections(1).id.get), 1000) === 0
        }

        inject[CollectionCommander].deleteCollection(collections(2))

        db.readOnlyMaster { implicit s =>
          collectionRepo.get(collections(2).id.get).state.value === "inactive"
          //          keepRepo.getByUser(user.id.get, None, None, Some(collections(2).id.get), 1000) === 0
        }
      }
    }

    "reorder tags" in {
      withDb(modules: _*) { implicit injector =>

        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val userValueRepo = inject[UserValueRepo]

        val (user, oldOrdering, tagA, tagB, tagC, tagD) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().saved

          val tagA = Collection(userId = user1.id.get, name = Hashtag("tagA"))
          val tagB = Collection(userId = user1.id.get, name = Hashtag("tagB"))
          val tagC = Collection(userId = user1.id.get, name = Hashtag("tagC"))
          val tagD = Collection(userId = user1.id.get, name = Hashtag("tagD"))

          val collections = collectionRepo.save(tagA) ::
            collectionRepo.save(tagB) ::
            collectionRepo.save(tagC) ::
            collectionRepo.save(tagD) ::
            Nil
          val collectionIds = collections.map(_.externalId).toSeq

          userValueRepo.save(UserValue(userId = user1.id.get, name = UserValueName.USER_COLLECTION_ORDERING, value = Json.stringify(Json.toJson(collectionIds))))
          (user1, collectionIds, tagA, tagB, tagC, tagD)
        }

        // First check collections were placed in DB correctly
        db.readOnlyMaster { implicit s =>
          val allCollectionIds = collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user.id.get).map(_.externalId)
          allCollectionIds === oldOrdering
        }

        // Move tagA to index 2 (move tag towards tail)
        db.readWrite { implicit s =>
          inject[CollectionCommander].setCollectionIndexOrdering(user.id.get, tagA.externalId, 2)
        }
        db.readOnlyMaster { implicit s =>
          val ordering = userValueRepo.getUserValue(user.id.get, UserValueName.USER_COLLECTION_ORDERING).get
          val newOrdering = tagB.externalId :: tagC.externalId :: tagA.externalId :: tagD.externalId :: Nil
          ordering.value === Json.stringify(Json.toJson(newOrdering))
        }

        // Move tagA to index 0 (move tag to head)
        db.readWrite { implicit session =>
          inject[CollectionCommander].setCollectionIndexOrdering(user.id.get, tagA.externalId, 0)
        }
        db.readOnlyMaster { implicit s =>
          val ordering = userValueRepo.getUserValue(user.id.get, UserValueName.USER_COLLECTION_ORDERING).get
          val newOrdering = tagA.externalId :: tagB.externalId :: tagC.externalId :: tagD.externalId :: Nil
          ordering.value === Json.stringify(Json.toJson(newOrdering))
        }

        // Move tagA to index 3 (move tag to tail)
        db.readWrite { implicit s =>
          inject[CollectionCommander].setCollectionIndexOrdering(user.id.get, tagA.externalId, 3)
        }
        db.readOnlyMaster { implicit s =>
          val ordering = userValueRepo.getUserValue(user.id.get, UserValueName.USER_COLLECTION_ORDERING).get
          val newOrdering = tagB.externalId :: tagC.externalId :: tagD.externalId :: tagA.externalId :: Nil
          ordering.value === Json.stringify(Json.toJson(newOrdering))
        }
      }
    }

    "paging all tags" in {
      withDb(modules: _*) { implicit injector =>
        val userValueRepo = inject[UserValueRepo]
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeper = KeepSource.keeper
        val collectionCommander = inject[CollectionCommander]

        val (user, oldOrdering, tag1, tag2, tag3) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().saved
          val lib1 = library().saved
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = false))

          val marioTag = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Mario")))
          val luigiTag = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Luigi")))
          val bowserTag = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Bowser")))
          val peachTag = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Peach")))

          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionIds = Seq(marioTag.externalId, luigiTag.externalId, bowserTag.externalId)

          userValueRepo.save(UserValue(userId = user1.id.get, name = UserValueName.USER_COLLECTION_ORDERING, value = Json.stringify(Json.toJson(collectionIds))))

          // keeps for Mario
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = marioTag.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = marioTag.id.get))
          collectionRepo.save(marioTag.copy(lastKeptTo = Some(t1)))

          // keeps for Peach
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = peachTag.id.get))
          collectionRepo.save(peachTag.copy(lastKeptTo = Some(t1.plusHours(1))))

          // keeps for Bowser
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = bowserTag.id.get))
          collectionRepo.save(bowserTag.copy(lastKeptTo = Some(t1.plusHours(1))))

          (user1, collectionIds, marioTag, luigiTag, bowserTag)
        }

        collectionCommander.pageCollections("whatever", 0, 3, user.id.get).map(_.name) === Seq("Bowser", "Peach", "Mario") // default to lastKeptAt, break ties by name, Luigi don't have keeps!
        collectionCommander.pageCollections("name", 0, 3, user.id.get).map(_.name) === Seq("Bowser", "Mario", "Peach") // Luigi don't have keeps!
        collectionCommander.pageCollections("num_keeps", 0, 3, user.id.get).map(_.name) === Seq("Mario", "Bowser", "Peach") // break ties by name, Luigi don't have keeps!
      }
    }
  }
}
