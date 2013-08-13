package com.keepit.reports

import play.api._
import play.api.libs.concurrent.Akka
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.common.time.zones.PT
import com.keepit.common.time._
import com.keepit.common.net._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.geckoboard._
import com.keepit.test._
import play.api.test.Helpers._
import com.google.inject._
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor.Scheduler
import org.joda.time.DateTime
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._
import org.specs2.mutable._
import play.api.libs.json._

class GeckoboardBookmarkWidgetsTest extends Specification with ShoeboxApplicationInjector {

  val hover = BookmarkSource("HOVER_KEEP")
  val initLoad = BookmarkSource("INIT_LOAD")

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, PT)
    val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, PT)

    db.readWrite {implicit s =>
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

      userExperimentRepo.save(UserExperiment(userId = user2.id.get, experimentType = State[ExperimentType]("admin")))
      userExperimentRepo.count === 1

      uriRepo.count === 0
      val uri1 = uriRepo.save(normalizedURIFactory.apply("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(normalizedURIFactory.apply("Amazon", "http://www.amazon.com/"))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
      bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
        uriId = uri2.id.get, source = initLoad, createdAt = t1.plusHours(50)))
      bookmarkRepo.save(Bookmark(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover, createdAt = t2.plusDays(1)))

      (user1, user2, uri1, uri2)
    }
  }

  "Query" should {
    "Keeps" in {
      running(new ShoeboxApplication()) {
        setup()
        inject[TotalKeepsPerHour].data === NumberAndSecondaryStat(2, 0)
        inject[TotalKeepsPerDay].data === NumberAndSecondaryStat(2, 0)
        inject[TotalKeepsPerWeek].data === NumberAndSecondaryStat(2, 0)
        inject[HoverKeepsPerWeek].data === NumberAndSecondaryStat(1, 0)
      }
    }
  }
}
