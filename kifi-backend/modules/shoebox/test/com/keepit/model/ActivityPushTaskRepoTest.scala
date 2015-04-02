package com.keepit.model

import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxTestInjector }
import org.joda.time.{ DateTime, LocalTime }
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit._

class ActivityPushTaskRepoTest extends Specification with ShoeboxApplicationInjector {

  "ActivityPushTaskRepo" should {
    "save and load model" in {
      running(new ShoeboxApplication()) {
        val repo = inject[ActivityPushTaskRepo]
        val now = new DateTime(2015, 2, 4, 5, 6, 7, DEFAULT_DATE_TIME_ZONE)
        val (user1, user2, user3, a1, a2, a3, a4, a5, a6) = db.readWrite { implicit rw =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val user4 = user().saved
          val user5 = user().saved
          val user6 = user().saved
          val a1 = repo.save(ActivityPushTask(userId = user1.id.get, lastPush = None, lastActiveDate = now, lastActiveTime = now.toLocalTime, nextPush = Some(now.plusDays(1)), backoff = Option(Duration(2, DAYS))))
          val a2 = repo.save(ActivityPushTask(userId = user2.id.get, lastPush = None, lastActiveDate = now.plusHours(2), lastActiveTime = now.plusHours(2).toLocalTime, nextPush = Some(now.plusDays(2)), backoff = Option(Duration(2, DAYS))))
          val a3 = repo.save(ActivityPushTask(userId = user3.id.get, lastPush = None, lastActiveDate = now.plusHours(4), lastActiveTime = now.plusHours(4).toLocalTime, nextPush = Some(now.plusDays(3)), backoff = Option(Duration(2, DAYS))))
          val a4 = repo.save(ActivityPushTask(userId = user4.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now, lastActiveTime = now.toLocalTime, nextPush = Some(now.plusDays(4)), backoff = Option(Duration(2, DAYS))))
          val a5 = repo.save(ActivityPushTask(userId = user5.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now.plusHours(2), lastActiveTime = now.plusHours(2).toLocalTime, nextPush = Some(now.plusDays(5)), backoff = Option(Duration(2, DAYS))))
          val a6 = repo.save(ActivityPushTask(userId = user6.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now.plusHours(4), lastActiveTime = now.plusHours(4).toLocalTime, nextPush = Some(now.plusDays(6)), backoff = Option(Duration(2, DAYS))))
          (user1, user2, user3, a1, a2, a3, a4, a5, a6)
        }
        val clock = inject[FakeClock]

        db.readOnlyMaster { implicit s =>
          repo.getByUser(user1.id.get).get === a1
          repo.getByUser(user2.id.get).get === a2
          repo.getByUser(user3.id.get).get === a3
          clock.push(now.minusDays(10)) // far into the past
          repo.getBatchToPush(10).isEmpty === true
        }

        db.readWrite { implicit s =>
          clock.push(now)
          repo.getBatchToPush(10).length === 0

          clock.push(now.plusDays(2))
          repo.getBatchToPush(10) === Seq(a1.id.get)

          clock.push(now.plusDays(10))
          repo.getBatchToPush(10).length === 6

          repo.save(a2.copy(nextPush = Some(now)))
          clock.push(now.plusSeconds(1))
          repo.getBatchToPush(10) === Seq(a2.id.get)
        }
      }
    }
  }
}
