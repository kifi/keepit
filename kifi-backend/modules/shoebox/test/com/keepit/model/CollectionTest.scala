package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.cache.{ HashMapMemoryCacheModule, ShoeboxCacheModule }
import com.keepit.common.db.{ FakeSlickModule, TestDbInfo, Id, SequenceNumber }
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.test.{ ShoeboxInjectionHelpers, CommonTestInjector, DbInjectionHelper }
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

class CollectionTest extends Specification with CommonTestInjector with DbInjectionHelper with ShoeboxInjectionHelpers {

  val modules = FakeSlickModule(TestDbInfo.dbInfo) :: FakeHeimdalServiceClientModule() :: FakeSlickModule(TestDbInfo.dbInfo) ::
    ShoeboxCacheModule(HashMapMemoryCacheModule()) :: FakeElizaServiceClientModule() :: Nil

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withCreatedAt(t1).withName("Andrew", "Conner").withUsername("test").saved
      val user2 = UserFactory.user().withCreatedAt(t2).withName("Eishay", "Smith").withUsername("test2").saved

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

      val hover = KeepSource.keeper

      val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
        urlId = url1.id.get, uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
        urlId = url2.id.get, uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50),
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

      val coll1 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Cooking"), createdAt = t1))
      val coll2 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Apparel"), createdAt = t1))
      val coll3 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Scala"), createdAt = t2))
      val coll4 = collectionRepo.save(Collection(userId = user2.id.get, name = Hashtag("Scala"), createdAt = t2))

      (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4)
    }
  }

  "collections" should {
    "binary serialization" in {
      val coll1 = Collection(id = Some(Id[Collection](1)), userId = Id[User](1), name = Hashtag("Cooking"))
      val coll2 = Collection(id = Some(Id[Collection](2)), userId = Id[User](1), name = Hashtag("Apparel"))
      val coll3 = Collection(id = Some(Id[Collection](3)), userId = Id[User](1), name = Hashtag("Scala"))
      val coll4 = Collection(id = Some(Id[Collection](4)), userId = Id[User](1), name = Hashtag("Java"))
      val collectionSummaries = Seq(coll1.summary, coll2.summary, coll3.summary, coll4.summary)
      val formatter = new CollectionSummariesFormat()
      val binary = formatter.writes(Some(collectionSummaries))
      binary.size === 224
      val deserialized = formatter.reads(binary).get
      deserialized(0) === collectionSummaries(0)
      deserialized(1) === collectionSummaries(1)
      deserialized(2) === collectionSummaries(2)
      deserialized(3) === collectionSummaries(3)
    }
    "allow the keepRepo to query by a specific collection" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll2.id.get))
        }
        db.readOnlyMaster { implicit s =>
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Apparel", "Cooking", "Scala")
          collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Apparel", "Cooking", "Scala")
          keepRepo.getByUserAndCollection(user1.id.get, coll1.id.get, None, None, 5) must haveLength(2)
          keepRepo.getByUser(user1.id.get, None, None, 5) must haveLength(2)
          keepRepo.getByUserAndCollection(user1.id.get, coll2.id.get, None, None, 5) must haveLength(1)
          keepRepo.getByUserAndCollection(user1.id.get, coll3.id.get, None, None, 5) must beEmpty
        }
      }
    }
    "work" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll3.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll1.id.get))
        }
        db.readOnlyMaster { implicit s =>
          collectionRepo.getBookmarkCounts(Set(coll1.id.get, coll2.id.get, coll3.id.get)) === Map(coll1.id.get -> 2, coll2.id.get -> 0, coll3.id.get -> 1)
        }
        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getKeepsForTag(coll1.id.get).toSet === Set(bookmark1.id.get, bookmark2.id.get)
          keepToCollectionRepo.getUriIdsInCollection(coll1.id.get).toSet === Set(KeepUriAndTime(bookmark1.uriId, bookmark1.createdAt), KeepUriAndTime(bookmark2.uriId, bookmark2.createdAt))
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Scala", "Apparel")
          collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Scala", "Apparel")
          keepToCollectionRepo.count(coll1.id.get) === 2
        }
        db.readWrite { implicit s =>
          keepRepo.save(bookmark1.withActive(false))
        }
        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.count(coll1.id.get) === 1
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s =>
            collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
            collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          }
        }

        db.readWrite { implicit s =>
          keepToCollectionRepo.getByKeep(bookmark1.id.get).groupBy(_.collectionId).foreach {
            case (collectionId, _) => collectionRepo.collectionChanged(collectionId, inactivateIfEmpty = false)
          }
        }
        db.readOnlyMaster { implicit s =>
          collectionRepo.getBookmarkCount(coll1.id.get) === 1
          collectionRepo.getBookmarkCounts(Set(coll1.id.get, coll2.id.get, coll3.id.get)) === Map(coll1.id.get -> 1, coll2.id.get -> 0, coll3.id.get -> 1)
        }

        coll2.isActive must beTrue
        db.readWrite { implicit s =>
          collectionRepo.collectionChanged(coll2.id.get, inactivateIfEmpty = true)
        }.isActive must beFalse

      }
    }
    "separate collections by user" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll3.id.get))
          collectionRepo.getByUserAndName(user1.id.get, Hashtag("Scala")).get.id.get === coll3.id.get
          collectionRepo.getByUserAndName(user2.id.get, Hashtag("Scala")).get.id.get === coll4.id.get
          keepToCollectionRepo.getKeepsForTag(coll3.id.get).length === 1
          keepToCollectionRepo.getUriIdsInCollection(coll3.id.get).length === 1
          keepToCollectionRepo.getKeepsForTag(coll4.id.get).length === 0
          keepToCollectionRepo.getUriIdsInCollection(coll4.id.get).length === 0
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll4.id.get))
          keepToCollectionRepo.getKeepsForTag(coll4.id.get).length === 1
          keepToCollectionRepo.getUriIdsInCollection(coll4.id.get).length === 1
          collectionRepo.getByUserAndName(user2.id.get, Hashtag("Cooking")) must beNone
        }
      }
    }
    "delete tag from keep" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          collectionRepo.getUnfortunatelyIncompleteTagSummariesByUser(user1.id.get).map(_.name.tag).toSet === Set("Cooking", "Apparel", "Scala")
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark2.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.getKeepsForTag(coll1.id.get).toSet === Set(bookmark1.id.get, bookmark2.id.get)
          keepToCollectionRepo.getUriIdsInCollection(coll1.id.get).toSet === Set(KeepUriAndTime(bookmark1.uriId, bookmark1.createdAt), KeepUriAndTime(bookmark2.uriId, bookmark2.createdAt))
          keepToCollectionRepo.remove(bookmark1.id.get, coll1.id.get)
          keepToCollectionRepo.getKeepsForTag(coll1.id.get).toSet === Set(bookmark2.id.get)
          keepToCollectionRepo.getUriIdsInCollection(coll1.id.get).toSet === Set(KeepUriAndTime(bookmark2.uriId, bookmark2.createdAt))
          keepToCollectionRepo.remove(bookmark1.id.get, coll1.id.get) should not(throwAn[Exception])
        }
      }
    }
    "get and cache collection ids for a bookmark" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll4.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll3.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll2.id.get))
          collectionRepo.save(coll4.copy(state = CollectionStates.INACTIVE))
        }
        db.readOnlyMaster { implicit s =>
          keepToCollectionRepo.getCollectionsForKeep(bookmark1.id.get).toSet ===
            Set(coll1.id.get, coll2.id.get, coll3.id.get)
        }
        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s =>
            keepToCollectionRepo.getCollectionsForKeep(bookmark1.id.get).toSet ===
              Set(coll1.id.get, coll2.id.get, coll3.id.get)
          }
        }
      }
    }
    "increment sequence number on save" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          val n = collectionRepo.save(coll1.withUpdateTime(currentDateTime)).seq.value
          n must be > coll1.seq.value
          n must be > coll2.seq.value
        }
      }
    }
    "update sequence number when keeps are added or removed, and when keeps' uriIds are changed" in {
      skipped("No longer automatically done")
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        val newSeqNum = db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = coll1.id.get))
          val n = collectionRepo.get(coll1.id.get).seq.value
          n must be > coll1.seq.value
          n must be > coll2.seq.value
          n
        }
        val latestSeqNum = db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(
            keepId = bookmark1.id.get, collectionId = coll1.id.get, state = KeepToCollectionStates.INACTIVE))
          val seq = collectionRepo.get(coll1.id.get).seq.value
          seq must be > newSeqNum
          seq
        }

        db.readOnlyMaster { implicit s =>
          collectionRepo.getCollectionsChanged(SequenceNumber(newSeqNum), 1000).map(_.id.get) === Seq(coll1.id.get)
        }
        db.readWrite { implicit s =>
          keepRepo.save(bookmark1.withNormUriId(bookmark2.uriId))
        }
        db.readOnlyMaster { implicit s =>
          collectionRepo.getCollectionsChanged(SequenceNumber(latestSeqNum), 1000).map(_.id.get) === Seq(coll1.id.get)
        }
      }
    }
    "ignore case in getting elements" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, _, _, _, _, _, _, _) = setup()
        db.readOnlyMaster { implicit s =>
          collectionRepo.getByUserAndName(user1.id.get, Hashtag("scala")) === collectionRepo.getByUserAndName(user1.id.get, Hashtag("Scala"))
        }
      }
    }

    "detect sensitive terms in tags" in {
      Hashtag("fuck you").isSensitive === true
      Hashtag("Fuck you").isSensitive === true
      Hashtag("you suck").isSensitive === true
      Hashtag("abüse").isSensitive === true
      Hashtag("how nice of you").isSensitive === false
    }
  }
}
