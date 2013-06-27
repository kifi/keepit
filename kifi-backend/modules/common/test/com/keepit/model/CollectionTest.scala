package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import com.google.inject.Injector
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.SequenceNumber
import com.keepit.common.time._
import com.keepit.common.time.zones.PT
import com.keepit.test.{EmptyApplication, TestDBRunner}

import play.api.Play.current
import play.api.test.Helpers._

class CollectionTest extends Specification with TestDBRunner {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, PT)

    db.readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith", createdAt = t2))

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(NormalizedURIFactory("Amazon", "http://www.amazon.com/"))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val hover = BookmarkSource("HOVER_KEEP")

      val bookmark1 = bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url,
        urlId = url1.id, uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
      val bookmark2 = bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url,
        urlId = url2.id, uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)))

      val coll1 = collectionRepo.save(Collection(userId = user1.id.get, name = "Cooking", createdAt = t1))
      val coll2 = collectionRepo.save(Collection(userId = user1.id.get, name = "Apparel", createdAt = t1))
      val coll3 = collectionRepo.save(Collection(userId = user1.id.get, name = "Scala", createdAt = t2))
      val coll4 = collectionRepo.save(Collection(userId = user2.id.get, name = "Scala", createdAt = t2))

      (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4)
    }
  }

  "collections" should {
    "allow the bookmarkRepo to query by a specific collection" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          collectionRepo.getByUser(user1.id.get).map(_.name).toSet === Set("Cooking", "Apparel", "Scala")
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = coll2.id.get))
          collectionRepo.getByUser(user1.id.get).map(_.name) === Seq("Apparel", "Cooking", "Scala")
          bookmarkRepo.getByUser(user1.id.get, None, None, coll1.id, 5) must haveLength(2)
          bookmarkRepo.getByUser(user1.id.get, None, None, None, 5) must haveLength(2)
          bookmarkRepo.getByUser(user1.id.get, None, None, coll2.id, 5) must haveLength(1)
          bookmarkRepo.getByUser(user1.id.get, None, None, coll3.id, 5) must beEmpty
        }
      }
    }
    "work" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          collectionRepo.getByUser(user1.id.get).map(_.name).toSet === Set("Cooking", "Apparel", "Scala")
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = coll3.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.getBookmarksInCollection(coll1.id.get).toSet === Set(bookmark1.id.get, bookmark2.id.get)
          collectionRepo.getByUser(user1.id.get).map(_.name) === Seq("Cooking", "Scala", "Apparel")
          keepToCollectionRepo.count(coll1.id.get) === 2
          bookmarkRepo.save(bookmark1.withActive(false))
          keepToCollectionRepo.count(coll1.id.get) === 1
        }
        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            collectionRepo.getByUser(user1.id.get).map(_.name).toSet == Set("Cooking", "Apparel", "Scala")
          }
        }
      }
    }
    "separate collections by user" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll3.id.get))
          collectionRepo.getByUserAndName(user1.id.get, "Scala").get.id.get === coll3.id.get
          collectionRepo.getByUserAndName(user2.id.get, "Scala").get.id.get === coll4.id.get
          keepToCollectionRepo.getBookmarksInCollection(coll3.id.get).length === 1
          keepToCollectionRepo.getBookmarksInCollection(coll4.id.get).length === 0
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark2.id.get, collectionId = coll4.id.get))
          keepToCollectionRepo.getBookmarksInCollection(coll4.id.get).length === 1
          collectionRepo.getByUserAndName(user2.id.get, "Cooking") must beNone
        }
      }
    }
    "get and cache collection ids for a bookmark" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll3.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll2.id.get))
          keepToCollectionRepo.getCollectionsForBookmark(bookmark1.id.get).toSet ===
            Set(coll1.id.get, coll2.id.get, coll3.id.get)
        }
        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            keepToCollectionRepo.getCollectionsForBookmark(bookmark1.id.get).toSet ===
              Set(coll1.id.get, coll2.id.get, coll3.id.get)
          }
        }
      }
    }
    "increment sequence number on save" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readWrite { implicit s =>
          val n = collectionRepo.save(coll1.withUpdateTime(currentDateTime)).seq.value
          n must be > coll1.seq.value
          n must be > coll2.seq.value
        }
      }
    }
    "update sequence number when keeps are added or removed, and when keeps' uriIds are changed" in {
      withDB() { implicit injector =>
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        val newSeqNum = db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = coll1.id.get))
          val n = collectionRepo.get(coll1.id.get).seq.value
          n must be > coll1.seq.value
          n must be > coll2.seq.value
          n
        }
        val latestSeqNum = db.readWrite { implicit s =>
          keepToCollectionRepo.save(KeepToCollection(
            bookmarkId = bookmark1.id.get, collectionId = coll1.id.get, state = KeepToCollectionStates.INACTIVE))
          val seq = collectionRepo.get(coll1.id.get).seq.value
          seq must be > newSeqNum
          seq
        }

        db.readOnly { implicit s =>
          collectionRepo.getCollectionsChanged(SequenceNumber(newSeqNum), 1000).map(_._1) === Seq(coll1.id.get)
        }
        db.readWrite { implicit s =>
          bookmarkRepo.save(bookmark1.withNormUriId(bookmark2.uriId))
        }
        db.readOnly { implicit s =>
          collectionRepo.getCollectionsChanged(SequenceNumber(latestSeqNum), 1000).map(_._1) === Seq(coll1.id.get)
        }
      }
    }
    "ignore case in getting elements" in {
      running(new EmptyApplication) {
        // TODO: figure out why this works but not withDB()
        implicit val injector = current.global.asInstanceOf[FortyTwoGlobal].injector
        val (user1, user2, bookmark1, bookmark2, coll1, coll2, coll3, coll4) = setup()
        db.readOnly { implicit s =>
          collectionRepo.getByUserAndName(user1.id.get, "scala") ===
              collectionRepo.getByUserAndName(user1.id.get, "Scala")
        }
      }
    }
  }
}
