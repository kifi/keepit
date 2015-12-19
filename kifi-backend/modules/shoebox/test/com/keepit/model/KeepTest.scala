package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.common.time._
import com.keepit.common.db.slick._

import com.keepit.test._
import com.google.inject.Injector
import com.keepit.common.db.{ Id }
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._

class KeepTest extends Specification with ShoeboxTestInjector {

  val hover = KeepSource.keeper
  val initLoad = KeepSource.bookmarkImport

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withCreatedAt(t1).withName("Andrew", "C").withUsername("test").saved
      val user2 = UserFactory.user().withCreatedAt(t2).withName("Eishay", "S").withUsername("test").saved

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
      val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
      val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/foo", Some("AmazonFoo")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val libPublic = LibraryFactory.library().withOwner(user1).withVisibility(LibraryVisibility.PUBLISHED).saved

      KeepFactory.keep().withTitle("G1").withUser(user1).withUri(uri1).withLibrary(libPublic).withKeptAt(t1 plusHours 3).withSource(KeepSource.keeper).saved
      KeepFactory.keep().withTitle("A1").withUser(user1).withUri(uri2).withLibrary(libPublic).withKeptAt(t1 plusHours 5).withSource(KeepSource.keeper).saved
      KeepFactory.keep().withTitle("A2").withUser(user1).withUri(uri3).withKeptAt(t1 plusHours 7).withSource(KeepSource.keeper).saved
      KeepFactory.keep().withUser(user2).withUri(uri1).withLibrary(libPublic).withKeptAt(t2 plusDays 1).withSource(KeepSource.bookmarkImport).saved

      (user1, user2, uri1, uri2, uri3, url1, url2, libPublic)
    }
  }

  "Bookmark" should {
    "load my keeps in pages before and after a given date" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, uri3, _, _, _) = setup()
        db.readOnlyMaster { implicit s =>
          val marks = keepRepo.getByUser(user1.id.get, None, None, 3)
          marks.map(_.uriId) === Seq(uri3.id.get, uri2.id.get, uri1.id.get)
          keepRepo.getByUser(user1.id.get, Some(marks(0).externalId), None, 5).map(_.uriId) === Seq(uri2.id.get, uri1.id.get)
          keepRepo.getByUser(user1.id.get, Some(marks(2).externalId), None, 5) must beEmpty
          keepRepo.getByUser(user1.id.get, Some(marks(2).externalId), None, 5) must beEmpty
          keepRepo.getByUser(user1.id.get, None, Some(marks(1).externalId), 5).map(_.uriId) === Seq(uri3.id.get)
          keepRepo.getByUser(user1.id.get, None, Some(marks(0).externalId), 5) must beEmpty
          keepRepo.getByUser(user1.id.get, None, None, 0) must beEmpty
          keepRepo.countPublicActiveByUri(uri1.id.get) === 2
          keepRepo.countPublicActiveByUri(uri2.id.get) === 1
          keepRepo.countPublicActiveByUri(uri3.id.get) === 0
          keepRepo.countPublicActiveByUri(Id[NormalizedURI](10000)) === 0
        }
      }
    }
    "load all" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _, lib) = setup()
        val cxAll = db.readOnlyMaster { implicit s =>
          keepRepo.all
        }
        val all = inject[Database].readOnlyMaster(implicit session => keepRepo.all)
        all.map(_.title) === Seq(Some("G1"), Some("A1"), Some("A2"), None)
      }
    }
    "load by user" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _, _) = setup()
        db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(user1.id.get).map(_.title) === Seq(Some("G1"), Some("A1"), Some("A2"))
          keepRepo.getByUser(user2.id.get).map(_.title) === Seq(None)
        }
      }
    }
    "load by uri" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, _, _, _) = setup()
        db.readOnlyMaster { implicit s =>
          keepRepo.getByUri(uri1.id.get).map(_.title) === Seq(Some("G1"), None)
          keepRepo.getByUri(uri2.id.get).map(_.title) === Seq(Some("A1"))
        }
      }
    }
    "count all" in {
      withDb() { implicit injector =>
        setup()
        db.readOnlyMaster { implicit s =>
          keepRepo.count(s) === 4
        }
      }
    }
    "count all by time" in {
      withDb(FakeClockModule()) { implicit injector =>
        setup()
        val clock = inject[FakeClock]
        db.readOnlyMaster { implicit s =>
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
        db.readOnlyMaster { implicit s =>
          keepRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, initLoad) === 1
          keepRepo.getCountByTimeAndSource(clock.now.minusHours(3), clock.now, hover) === 3
        }
      }
    }
    "count by user" in {
      withDb() { implicit injector =>
        val (user1, user2, _, _, _, _, _, _) = setup()
        db.readOnlyMaster { implicit s =>
          keepRepo.getCountByUser(user1.id.get) === 3
          keepRepo.getCountByUser(user2.id.get) === 1
        }
      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, _, url1, _, _) = setup()
        db.readWrite { implicit s =>
          keepRepo.count === 4
          val bm = keepRepo.getByUriAndUser(uri1.id.get, user1.id.get)
          keepRepo.deactivate(bm.get)
        }
        db.readWrite { implicit s =>
          keepRepo.all.size === 3
          keepRepo.count === 3
        }
        db.readWrite { implicit s =>
          KeepFactory.keep().withTitle("G1").withUser(user1).withUri(uri1).saved
        }
        db.readWrite { implicit s =>
          keepRepo.count === 4
        }
      }
    }

    "get by exclude state should work" in {
      withDb() { implicit injector =>
        val (user1, user2, uri1, uri2, url1, _, _, _) = setup()
        db.readWrite { implicit s =>
          val bm = keepRepo.getByUriAndUser(uri1.id.get, user1.id.get)
          keepRepo.deactivate(bm.get)
        }

        db.readOnlyMaster { implicit s =>
          keepRepo.getByUriAndUser(uri1.id.get, user1.id.get).size === 0
        }
      }
    }

    "get latest keeps uri by user" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readWrite { implicit s =>
          val user = UserFactory.user().withCreatedAt(t1).withName("Andrew", "C").withUsername("test").saved
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash("http://www.kifi.com/", Some("Kifi")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val libDiscoverable = LibraryFactory.library().withVisibility(LibraryVisibility.DISCOVERABLE).withOwner(user).saved

          KeepFactory.keep().withUser(user).withLibrary(libDiscoverable).withUri(uri1).withKeptAt(t1 plusMinutes 3).saved
          KeepFactory.keep().withUser(user).withLibrary(libDiscoverable).withUri(uri2).withKeptAt(t1 plusMinutes 9).saved
          KeepFactory.keep().withUser(user).withUri(uri3).withKeptAt(t1 plusMinutes 6).saved
        }

        db.readOnlyMaster { implicit s =>
          var uris = keepRepo.getLatestKeepsURIByUser(Id[User](1), 2, includePrivate = true)
          uris.map { _.id } === Seq(2, 3)
          uris = keepRepo.getLatestKeepsURIByUser(Id[User](1), 2, includePrivate = false)
          uris.map { _.id } === Seq(2, 1)
        }
      }

    }
  }
}
