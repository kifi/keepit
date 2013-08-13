package com.keepit.reports

import org.joda.time.{Days, Months}
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

class GeckoboardUserWidgetsTest extends Specification with ShoeboxApplicationInjector {

  val hover = BookmarkSource("HOVER_KEEP")
  val initLoad = BookmarkSource("INIT_LOAD")

  def setup(clock: FakeClock)(implicit injector: Injector) = {
    db.readWrite {implicit s =>
      clock += Days.ONE
      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C"))
      clock += Days.TWO
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
      val user3 = userRepo.save(User(firstName = "Shachaf", lastName = "Smith"))
      val user4 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
      clock += Days.TWO
      userRepo.save(User(firstName = "Dafna", lastName = "Smith"))
      clock += Days.TWO
      userRepo.save(User(firstName = "Jack", lastName = "Brown"))

      userExperimentRepo.save(UserExperiment(userId = user2.id.get, experimentType = State[ExperimentType]("admin")))
      userExperimentRepo.count === 1

      uriRepo.count === 0
      val uri1 = uriRepo.save(normalizedURIFactory.apply("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(normalizedURIFactory.apply("Amazon", "http://www.amazon.com/"))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      clock += Months.ONE
      bookmarkRepo.save(Bookmark(title = Some("Z1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover))
      clock += Months.ONE
      bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover))
      bookmarkRepo.save(Bookmark(title = Some("B1"), userId = user3.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover))
      clock += Days.days(10)
      bookmarkRepo.save(Bookmark(title = Some("C1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover))
      bookmarkRepo.save(Bookmark(title = Some("D1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
        uriId = uri2.id.get, source = initLoad))
      bookmarkRepo.save(Bookmark(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id,
        uriId = uri1.id.get, source = hover))
      userRepo.save(User(firstName = "Joe1", lastName = "Brown"))
      userRepo.save(User(firstName = "Joe2", lastName = "Brown"))

      (user1, user2, uri1, uri2)
    }
  }

  "Query" should {
    "Users" in {
      running(new ShoeboxApplication(FakeClockModule())) {
        val clock = inject[FakeClock]
        setup(clock)
        clock += Days.ONE
        val data = inject[RetentionOverMonth].data
        println(data.json)
        data === SparkLine("Retention Per Month", 40 ,Vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 100, 33, 33, 25, 25, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40))
      }
    }
  }
}
