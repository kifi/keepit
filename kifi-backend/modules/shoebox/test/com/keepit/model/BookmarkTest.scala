package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.time._
import com.keepit.common.db.slick._
import com.keepit.common.time.zones.PT
import com.keepit.test._
import com.google.inject.Injector

class BookmarkTest extends Specification with ShoeboxTestInjector {

  val hover = BookmarkSource("HOVER_KEEP")
  val initLoad = BookmarkSource("INIT_LOAD")

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, PT)

    db.readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
      bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
        uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)))
      bookmarkRepo.save(Bookmark(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1)))

      (user1, user2, uri1, uri2, url1, url2)
    }
  }

  "Bookmark" should {
    "load my keeps in pages before and after a given date" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        db.readOnly { implicit s =>
          val marks = bookmarkRepo.getByUser(user1.id.get, None, None, None, 2)
          marks.map(_.uriId) === Seq(uri2.id.get, uri1.id.get)
          bookmarkRepo.getByUser(user1.id.get, Some(marks(0).externalId), None, None, 5).map(_.uriId) ===
              Seq(uri1.id.get)
          bookmarkRepo.getByUser(user1.id.get, Some(marks(1).externalId), None, None, 5) must beEmpty
          bookmarkRepo.getByUser(user1.id.get, Some(marks(1).externalId), None, None, 5) must beEmpty
          bookmarkRepo.getByUser(user1.id.get, None, Some(marks(1).externalId), None, 5).map(_.uriId) ===
              Seq(uri2.id.get)
          bookmarkRepo.getByUser(user1.id.get, None, Some(marks(0).externalId), None, 5) must beEmpty
          bookmarkRepo.getByUser(user1.id.get, None, None, None, 0) must beEmpty
        }
      }
    }
    "load all" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        val cxAll = db.readOnly {implicit s =>
          bookmarkRepo.all
        }
        println(cxAll mkString "\n")
        val all = inject[Database].readOnly(implicit session => bookmarkRepo.all)
        all.map(_.title) === Seq(Some("G1"), Some("A1"), None)
      }
    }
    "load by user" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getByUser(user1.id.get).map(_.title) === Seq(Some("G1"), Some("A1"))
          bookmarkRepo.getByUser(user2.id.get).map(_.title) === Seq(None)
        }
      }
    }
    "load by uri" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getByUri(uri1.id.get).map(_.title) === Seq(Some("G1"), None)
          bookmarkRepo.getByUri(uri2.id.get).map(_.title) === Seq(Some("A1"))
        }
      }
    }
    "count all" in {
      withDb() { implicit injector =>
        setup()
        db.readOnly {implicit s =>
          bookmarkRepo.count(s) === 3
        }
      }
    }
    "count all by time" in {
      withDb(FakeClockModule()) { implicit injector =>
        setup()
        val clock = inject[FakeClock]
        db.readOnly {implicit s =>
          bookmarkRepo.getCountByTime(clock.now.minusHours(3), clock.now) === 3
          bookmarkRepo.getCountByTime(clock.now.minusHours(6), clock.now.minusHours(3)) === 0
        }
      }
    }
    "count all by time and source" in {
      withDb(FakeClockModule()) { implicit injector =>
        setup()
        val clock = inject[FakeClock]
        db.readOnly {implicit s =>
          bookmarkRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, initLoad) === 1
          bookmarkRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, hover) === 2
        }
      }
    }
    "count by user" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getCountByUser(user1.id.get) === 2
          bookmarkRepo.getCountByUser(user2.id.get) === 1
        }
      }
    }
    "count mutual keeps" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getNumMutual(user1.id.get, user2.id.get) === 1
          bookmarkRepo.getNumMutual(user2.id.get, user1.id.get) === 1
        }
      }
    }
    
    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, url1, _) = setup()
        db.readWrite{ implicit s =>
          bookmarkRepo.count == 3
          val bm = bookmarkRepo.getByUriAndUser(uri1.id.get, user1.id.get)
          bookmarkRepo.delete(bm.get.id.get)
          bookmarkRepo.count == 2
          val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
          bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
          uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
          bookmarkRepo.count == 3
        }
      }
    }
  }
}
