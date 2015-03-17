package com.keepit.model

import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxTestInjector }
import org.joda.time.{ DateTime, LocalTime }
import org.specs2.mutable.Specification
import play.api.test.Helpers._

class ActivityPushTaskRepoTest extends Specification with ShoeboxApplicationInjector {

  args(skipAll = true)

  "ActivityPushTaskRepo" should {
    "save and load model" in {
      running(new ShoeboxApplication()) {
        val repo = inject[ActivityPushTaskRepo]
        val now = new DateTime(2015, 2, 4, 5, 6, 7, DEFAULT_DATE_TIME_ZONE)
        val (user1, user2, user3, a1, a2, a3, a4, a5, a6) = db.readWrite { implicit rw =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val a1 = repo.save(ActivityPushTask(userId = user1.id.get, lastActiveDate = now, lastActiveTime = now.toLocalTime))
          val a2 = repo.save(ActivityPushTask(userId = user2.id.get, lastActiveDate = now.plusHours(2), lastActiveTime = now.plusHours(2).toLocalTime))
          val a3 = repo.save(ActivityPushTask(userId = user3.id.get, lastActiveDate = now.plusHours(4), lastActiveTime = now.plusHours(4).toLocalTime))
          val a4 = repo.save(ActivityPushTask(userId = user1.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now, lastActiveTime = now.toLocalTime))
          val a5 = repo.save(ActivityPushTask(userId = user2.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now.plusHours(2), lastActiveTime = now.plusHours(2).toLocalTime))
          val a6 = repo.save(ActivityPushTask(userId = user3.id.get, lastPush = Some(now.plusDays(2)), lastActiveDate = now.plusHours(4), lastActiveTime = now.plusHours(4).toLocalTime))
          (user1, user2, user3, a1, a2, a3, a4, a5, a6)
        }
        db.readOnlyMaster { implicit s =>
          repo.getByUser(user1.id.get).get === a1
          repo.getByUser(user2.id.get).get === a2
          repo.getByUser(user3.id.get).get === a3
          repo.getByPushAndActivity(now.plusHours(1), now.minusHours(1).toLocalTime, 10).isEmpty === true
        }
        db.readWrite { implicit s =>
          repo.save(repo.getByUser(user1.id.get).get.copy(lastPush = Some(new DateTime(2015, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE))))
          repo.save(repo.getByUser(user2.id.get).get.copy(lastPush = Some(new DateTime(2014, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE))))
        }
        db.readWrite { implicit s =>
          repo.getByPushAndActivity(now, now.toLocalTime.minusHours(1), 10) === Seq()
          repo.getByPushAndActivity(now, now.toLocalTime.plusHours(1), 10) === Seq(a1.id.get)
          repo.getByPushAndActivity(now.minusDays(1), now.toLocalTime.plusHours(1), 10) === Seq(a1.id.get)
          repo.getByPushAndActivity(now.plusDays(1), now.toLocalTime.plusHours(1), 10) === Seq(a1.id.get)

          repo.getByPushAndActivity(now.plusDays(1), now.toLocalTime.plusHours(5), 10) === Seq(a1.id.get, a2.id.get, a3.id.get)
          repo.getByPushAndActivity(now.plusDays(3), now.toLocalTime.plusHours(5), 10) === Seq(a1.id.get, a2.id.get, a3.id.get, a4.id.get, a5.id.get, a6.id.get)
          repo.getByPushAndActivity(now.plusDays(3), now.toLocalTime.plusHours(3), 10) === Seq(a1.id.get, a2.id.get, a4.id.get, a5.id.get)
        }
      }
    }
  }
}
