package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.time._
import com.keepit.common.db.slick._

import com.keepit.test._
import com.google.inject.Injector
import com.keepit.common.db.Id

class KeepTest extends Specification with ShoeboxTestInjector {

  val hover = KeepSource.keeper
  val initLoad = KeepSource.bookmarkImport

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/foo", Some("AmazonFoo")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
      keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)))
      keepRepo.save(Keep(title = Some("A2"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri3.id.get, source = hover, createdAt = t1.plusHours(50), isPrivate = true))
      keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1)))

      (user1, user2, uri1, uri2, uri3, url1, url2)
    }
  }

  "Bookmark" should {
    "load my keeps in pages before and after a given date" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, uri3, _, _) = setup()
        db.readOnly { implicit s =>
          val marks = keepRepo.getByUser(user1.id.get, None, None, 3)
          marks.map(_.uriId) === Seq(uri3.id.get, uri2.id.get, uri1.id.get)
          keepRepo.getByUser(user1.id.get, Some(marks(0).externalId), None, 5).map(_.uriId) === Seq(uri2.id.get, uri1.id.get)
          keepRepo.getByUser(user1.id.get, Some(marks(2).externalId), None, 5) must beEmpty
          keepRepo.getByUser(user1.id.get, Some(marks(2).externalId), None, 5) must beEmpty
          keepRepo.getByUser(user1.id.get, None, Some(marks(1).externalId), 5).map(_.uriId) === Seq(uri3.id.get)
          keepRepo.getByUser(user1.id.get, None, Some(marks(0).externalId), 5) must beEmpty
          keepRepo.getByUser(user1.id.get, None, None, 0) must beEmpty
        }
      }
    }
    "load all" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _) = setup()
        val cxAll = db.readOnly {implicit s =>
          keepRepo.all
        }
        val all = inject[Database].readOnly(implicit session => keepRepo.all)
        all.map(_.title) === Seq(Some("G1"), Some("A1"), Some("A2"), None)
      }
    }
    "load by user" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _) = setup()
        db.readOnly {implicit s =>
          keepRepo.getByUser(user1.id.get).map(_.title) === Seq(Some("G1"), Some("A1"), Some("A2"))
          keepRepo.getByUser(user2.id.get).map(_.title) === Seq(None)
        }
      }
    }
    "load by uri" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _) = setup()
        db.readOnly {implicit s =>
          keepRepo.getByUri(uri1.id.get).map(_.title) === Seq(Some("G1"), None)
          keepRepo.getByUri(uri2.id.get).map(_.title) === Seq(Some("A1"))
        }
      }
    }
    "count all" in {
      withDb() { implicit injector =>
        setup()
        db.readOnly {implicit s =>
          keepRepo.count(s) === 4
        }
      }
    }
    "count all by time" in {
      withDb(FakeClockModule()) { implicit injector =>
        setup()
        val clock = inject[FakeClock]
        db.readOnly {implicit s =>
          val now = clock.now
          keepRepo.getCountByTime(now.minusHours(3), now.plusMinutes(1)) === 4
          keepRepo.getCountByTime(now.minusHours(6), now.minusHours(3)) === 0
        }
      }
    }
    "count all by time and source" in {
      withDb(FakeClockModule()) { implicit injector =>
        setup()
        val clock = inject[FakeClock]
        db.readOnly {implicit s =>
          keepRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, initLoad) === 1
          keepRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, hover) === 3
        }
      }
    }
    "count by user" in {
      withDb() { implicit injector =>
        val (user1, user2, _, _, _, _, _) = setup()
        db.readOnly {implicit s =>
          keepRepo.getCountByUser(user1.id.get) === 3
          keepRepo.getCountByUser(user2.id.get) === 1
          keepRepo.getPrivatePublicCountByUser(user1.id.get) === (1, 2)
          keepRepo.getPrivatePublicCountByUser(user2.id.get) === (0, 1)
        }
      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, url1, _) = setup()
        db.readWrite{ implicit s =>
          keepRepo.count === 4
          val bm = keepRepo.getByUriAndUser(uri1.id.get, user1.id.get)
          keepRepo.delete(bm.get.id.get)
        }
        db.readWrite{ implicit s =>
          keepRepo.all.size === 3
          keepRepo.count === 3
        }
        db.readWrite{ implicit s =>
          val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
        }
        db.readWrite{ implicit s =>
          keepRepo.count === 4
        }
      }
    }

    "get by exclude state should work" in {
       withDb() { implicit injector =>
         val (user1, user2, uri1, uri2, url1, _, _) = setup()
         db.readWrite{ implicit s =>
           val bm = keepRepo.getByUriAndUser(uri1.id.get, user1.id.get)
           keepRepo.save(bm.get.withActive(false))
         }

         db.readOnly{ implicit s =>
           keepRepo.getByUriAndUser(uri1.id.get, user1.id.get).size === 0
           keepRepo.getPrimaryByUriAndUser(uri1.id.get, user1.id.get).size === 1
        }
      }
    }

    "get the latest updated bookmark for a specific uri" in {
      withDb() { implicit injector =>
        val (uri, uriId, url, firstUserId, secondUserId, urlId) = db.readWrite{ implicit s =>
          val uri = uriRepo.save(NormalizedURI.withHash("http://www.kifi.com"))
          val urlId = urlRepo.save(URL(url = uri.url, domain = Some("kifi.com"), normalizedUriId = uri.id.get)).id.get
          val uriId = uri.id.get
          val url = uri.url
          val firstUserId = userRepo.save(User(firstName = "Léo", lastName = "Grimaldi")).id.get
          val secondUserId = userRepo.save(User(firstName = "Eishay", lastName = "Smith")).id.get
          (uri, uriId, url, firstUserId, secondUserId, urlId)
        }
        db.readOnly{ implicit s =>
          keepRepo.latestBookmark(uriId) === None
        }
        val firstUserBookmark = db.readWrite{ implicit s =>
          keepRepo.save(Keep(userId = firstUserId, uriId = uriId, urlId = urlId, url = url, source = hover))
        }
        db.readOnly{ implicit s =>
          keepRepo.latestBookmark(uriId).flatMap(_.id) === firstUserBookmark.id
        }
        val secondUserBookmark = db.readWrite{ implicit s =>
          keepRepo.save(Keep(userId = secondUserId, uriId = uriId, urlId = urlId, url = url, source = hover))
        }
        db.readOnly{ implicit s =>
          keepRepo.latestBookmark(uriId).flatMap(_.id) === secondUserBookmark.id
        }
        val latestBookmark = db.readWrite{ implicit s =>
          keepRepo.save(firstUserBookmark)
        }
        db.readOnly{ implicit s =>
          keepRepo.latestBookmark(uriId).flatMap(_.id) === latestBookmark.id
        }
      }
    }
  }
}
