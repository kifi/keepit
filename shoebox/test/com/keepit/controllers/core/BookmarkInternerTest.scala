package com.keepit.controllers.core

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.common.db.slick._
import org.apache.zookeeper.CreateMode
import play.api.libs.json.Json
import com.keepit.common.healthcheck._

class BookmarkInternerTest extends Specification with DbRepos {

  "BookmarkInterner" should {
    "persist bookmark" in {
      running(new EmptyApplication().withFakeScraper()) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internBookmarks(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          ), user, Set(), "EMAIL")
        db.readWrite { implicit db =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 1
          bookmarkRepo.get(bookmarks.head.id.get) === bookmarks.head
          bookmarkRepo.all.size === 1
        }
      }
    }

    "persist bookmarks" in {
      running(new EmptyApplication().withFakeScraper()) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val bookmarkInterner = inject[BookmarkInterner]
        val bookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
            "url" -> "http://42go.com",
            "isPrivate" -> true
          ), Json.obj(
            "url" -> "http://kifi.com",
            "isPrivate" -> false
          )), user, Set(), "EMAIL")
        db.readWrite { implicit db =>
          userRepo.get(user.id.get) === user
          bookmarks.size === 2
          bookmarkRepo.all.size === 2
        }
      }
    }
  }

  "persist bookmarks with one bad url" in {
    running(new EmptyApplication().withFakeScraper()) {
      val user = db.readWrite { implicit db =>
        userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
      }
      val fakeHealthcheck = inject[FakeHealthcheck]
      fakeHealthcheck.errorCount() === 0
      val bookmarkInterner = inject[BookmarkInterner]
      val bookmarks = bookmarkInterner.internBookmarks(Json.arr(Json.obj(
          "url" -> "http://42go.com",
          "isPrivate" -> true
        ), Json.obj(
          "url" -> ("http://kifi.com/" + List.fill(300)("this_is_a_very_long_url/").mkString),
          "isPrivate" -> false
        ), Json.obj(
          "url" -> "http://kifi.com",
          "isPrivate" -> true
        )), user, Set(), "EMAIL")
      db.readWrite { implicit db =>
        bookmarks.size === 2
        bookmarkRepo.all.size === 2
      }
      fakeHealthcheck.errorCount() === 1
    }
  }

}
