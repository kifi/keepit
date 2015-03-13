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

  "ActivityPushTaskRepo" should {
    "save and load model" in {
      running(new ShoeboxApplication()) {
        val repo = inject[ActivityPushTaskRepo]
        val (user1, user2, user3, a1, a2, a3) = db.readWrite { implicit rw =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val a1 = repo.save(ActivityPushTask(userId = user1.id.get, lastActive = new DateTime(2015, 2, 4, 5, 6, 7, DEFAULT_DATE_TIME_ZONE)))
          val a2 = repo.save(ActivityPushTask(userId = user2.id.get, lastActive = new DateTime(2015, 2, 4, 7, 3, 7, DEFAULT_DATE_TIME_ZONE)))
          val a3 = repo.save(ActivityPushTask(userId = user3.id.get, lastActive = new DateTime(2015, 2, 4, 8, 4, 7, DEFAULT_DATE_TIME_ZONE)))
          (user1, user2, user3, a1, a2, a3)
        }
        db.readOnlyMaster { implicit s =>
          repo.getByUser(user1.id.get).get === a1
          repo.getByUser(user2.id.get).get === a2
          repo.getByUser(user3.id.get).get === a3
          repo.getByPushAndActivity(new DateTime(2015, 2, 3, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(5, 6, 7), 10).isEmpty === true
        }
        db.readWrite { implicit s =>
          repo.save(repo.getByUser(user1.id.get).get.copy(lastPush = Some(new DateTime(2015, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE))))
          repo.save(repo.getByUser(user2.id.get).get.copy(lastPush = Some(new DateTime(2014, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE))))
        }
        db.readWrite { implicit s =>
          repo.getByPushAndActivity(new DateTime(2015, 2, 3, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(10, 6, 7), 10) === Seq()
          repo.getByPushAndActivity(new DateTime(2015, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(10, 6, 7), 10) === Seq()
          repo.getByPushAndActivity(new DateTime(2015, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(1, 6, 7), 10) === Seq(a1.id.get)
          repo.getByPushAndActivity(new DateTime(2010, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(1, 6, 7), 10) === Seq(a1.id.get, a2.id.get)
          repo.getByPushAndActivity(new DateTime(2010, 2, 2, 1, 1, 1, DEFAULT_DATE_TIME_ZONE), new LocalTime(6, 6, 7), 10) === Seq(a2.id.get)
        }
      }
    }
  }
}
