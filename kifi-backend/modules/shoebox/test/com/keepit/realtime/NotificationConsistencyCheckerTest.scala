package com.keepit.realtime

import org.specs2.mutable.Specification

import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.model._
import com.keepit.test.{ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxInjectionHelpers, DeprecatedEmptyApplication}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.test.Helpers._
import play.api.libs.json.Json
import com.keepit.model.UserNotification
import com.keepit.model.UserNotificationDetails
import com.keepit.model.CommentRead
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.actor.TestActorSystemModule

class NotificationConsistencyCheckerTest extends TestKit(ActorSystem()) with Specification with ShoeboxApplicationInjector {
  "NotificationConsistencyChecker" should {
    "create healthcheck errors for unvisited notifications with read comments" in {
      running(new ShoeboxApplication(FakeMailModule(), TestActorSystemModule(Some(system)))) {
        val checker = inject[NotificationConsistencyChecker]
        val healthcheck = inject[FakeHealthcheck]
        val (user, nUri, comment, notif) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Smith"))
          val nUri = uriRepo.save(normalizedURIFactory.apply("http://www.42go.com/"))
          val comment = commentRepo.save(
            Comment(userId = user.id.get, uriId = nUri.id.get, text = "hey", pageTitle = "test"))
          val notif = notificationRepo.save(
            UserNotification(userId = user.id.get, details = UserNotificationDetails(Json.obj()),
              commentId = comment.id, category = UserNotificationCategories.MESSAGE, subsumedId = None,
              state = UserNotificationStates.DELIVERED))
          (user, nUri, comment, notif)
        }
        checker.verifyVisited()
        healthcheck.errors must beEmpty
        db.readWrite { implicit s =>
          commentReadRepo.save(
            CommentRead(userId = user.id.get, uriId = nUri.id.get, lastReadId = comment.id.get, parentId = comment.id))
        }
        checker.verifyVisited()
        healthcheck.errors must not beEmpty
        val nvisited = db.readWrite { implicit s =>
          notificationRepo.save(notif.withState(UserNotificationStates.VISITED))
        }
        healthcheck.resetErrorCount()
        checker.verifyVisited()
        healthcheck.errors must beEmpty
      }
    }
  }
}
