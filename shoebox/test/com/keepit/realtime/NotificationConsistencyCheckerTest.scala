package com.keepit.realtime

import org.specs2.mutable.Specification

import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.inject.inject
import com.keepit.model._
import com.keepit.test.{EmptyApplication, DbRepos}

import akka.actor.ActorSystem
import akka.testkit.TestKit
import play.api.Play.current
import play.api.test.Helpers.running
import play.api.libs.json.Json

class NotificationConsistencyCheckerTest extends TestKit(ActorSystem()) with Specification with DbRepos {
  "NotificationConsistencyChecker" should {
    "create healthcheck errors for unvisited notifications with read comments" in {
      running(new EmptyApplication().withTestActorSystem(system).withFakeMail()) {
        val checker = inject[NotificationConsistencyChecker]
        val healthcheck = inject[FakeHealthcheck]
        val (user, nUri, comment, notif) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Smith"))
          val nUri = uriRepo.save(NormalizedURIFactory("http://www.42go.com/"))
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
