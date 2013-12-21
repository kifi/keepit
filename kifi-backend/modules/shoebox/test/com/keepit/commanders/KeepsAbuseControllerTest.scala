package com.keepit.commanders

import com.keepit.test._
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizationService
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.Some

class KeepsAbuseControllerTest extends Specification with ShoeboxTestInjector {

  "KeepsAbuseControl" should {
    "check for global abuse not triggered" in {
      withDb() { implicit injector =>
        val db = inject[Database]
        val bookmarkRepo = inject[BookmarkRepo]
        val controller = new KeepsAbuseController(absoluteAlert = 2, absoluteError = 5, bookmarkRepo = bookmarkRepo, db = db)
        val user = db.readWrite {implicit s =>
          inject[UserRepo].save(User(firstName = "Dafna", lastName = "Smith"))
        }
        controller.inspact(user.id.get, 20)
        1 === 1
      }
    }

    "check for global abuse not triggered" in {
      withDb() { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val keeper = BookmarkSource.keeper
        val db = inject[Database]
        val bookmarkRepo = inject[BookmarkRepo]
        val controller = new KeepsAbuseController(absoluteAlert = 1, absoluteError = 2, bookmarkRepo = bookmarkRepo, db = db)
        val user = db.readWrite {implicit s =>
          inject[UserRepo].save(User(firstName = "Dafna", lastName = "Smith"))
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

          val normalizationService = inject[NormalizationService]
          val uri1 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.amazon.com/"), Some("Amazon")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.kifi.com/"), Some("kifi")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = BookmarkStates.ACTIVE))
          bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = BookmarkStates.ACTIVE))
          bookmarkRepo.save(Bookmark(title = Some("A3"), userId = user1.id.get, url = url3.url, urlId = url3.id,
            uriId = uri3.id.get, source = keeper, createdAt = t1.plusHours(50), state = BookmarkStates.ACTIVE))
          user1
        }

        { controller.inspact(user.id.get, 20) } must throwA[AbuseControlException]

        1 === 1
      }
    }

    "check for global abuse" in {
      withDb() { implicit injector =>
        val db = inject[Database]
        val bookmarkRepo = inject[BookmarkRepo]
        def createChecker(): Unit = new KeepsAbuseController(absoluteAlert = 10, absoluteError = 5, bookmarkRepo = bookmarkRepo, db = db)
        { createChecker() } must throwA[IllegalStateException]
        1 === 1
      }
    }
  }
}
