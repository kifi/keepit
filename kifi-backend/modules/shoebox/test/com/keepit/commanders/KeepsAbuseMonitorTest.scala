package com.keepit.commanders

import com.keepit.test._
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizationService
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.Some
import com.keepit.common.healthcheck.{ AirbrakeNotifier, FakeAirbrakeNotifier }
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession

class KeepsAbuseMonitorTest extends Specification with ShoeboxTestInjector {

  "KeepsAbuseControl" should {

    def prenormalize(url: String)(implicit injector: Injector): String = inject[NormalizationService].prenormalize(url).get

    "check for global abuse not triggered" in {
      withDb() { implicit injector =>
        val db = inject[Database]
        val keepRepo = inject[KeepRepo]
        val monitor = new KeepsAbuseMonitor(absoluteWarn = 200, absoluteError = 500, keepRepo = keepRepo, db = db, airbrake = inject[AirbrakeNotifier])
        val user = db.readWrite { implicit s =>
          inject[UserRepo].save(User(firstName = "Dafna", lastName = "Smith"))
        }
        monitor.inspect(user.id.get, 20)
        1 === 1
      }
    }

    "check for global abuse error triggered" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeper = KeepSource.keeper
        val db = inject[Database]
        val keepRepo = inject[KeepRepo]
        val monitor = new KeepsAbuseMonitor(absoluteWarn = 1, absoluteError = 2, keepRepo = keepRepo, db = db, airbrake = inject[AirbrakeNotifier])
        val user = db.readWrite { implicit s =>
          inject[UserRepo].save(User(firstName = "Dafna", lastName = "Smith"))
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.kifi.com/"), Some("kifi")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf")))

          keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          keepRepo.save(Keep(title = Some("A3"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          user1
        }

        { monitor.inspect(user.id.get, 20) } must throwA[AbuseMonitorException]
      }
    }

    "check for global abuse warn triggered" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeper = KeepSource.keeper
        val db = inject[Database]
        val keepRepo = inject[KeepRepo]
        val monitor = new KeepsAbuseMonitor(absoluteWarn = 1, absoluteError = 30, keepRepo = keepRepo, db = db, airbrake = inject[AirbrakeNotifier])
        val user = db.readWrite { implicit s =>
          inject[UserRepo].save(User(firstName = "Dafna", lastName = "Smith"))
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.kifi.com/"), Some("kifi")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf")))

          keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          keepRepo.save(Keep(title = Some("A3"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
          user1
        }
        val airbrake = inject[AirbrakeNotifier].asInstanceOf[FakeAirbrakeNotifier]
        airbrake.errorCount() === 0
        monitor.inspect(user.id.get, 20) //should not throw an exception
        airbrake.errorCount() === 1
      }
    }

    "check for bad configs" in {
      withDb() { implicit injector =>
        val db = inject[Database]
        val keepRepo = inject[KeepRepo]
        def createChecker(): Unit = new KeepsAbuseMonitor(absoluteWarn = 10, absoluteError = 5, keepRepo = keepRepo, db = db, airbrake = inject[AirbrakeNotifier]) { createChecker() } must throwA[IllegalStateException]
      }
    }
  }
}
