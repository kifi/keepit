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

class BookmarkInternerTest extends Specification with DbRepos {

  "BookmarkInterner" should {
    "persist bookmarks" in {
      running(new EmptyApplication().withFakeHealthcheck().withFakeScraper()) {
        val user = db.readWrite { implicit db =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        db.readWrite { implicit db =>
          userRepo.get(user.id.get) === user
          val bookmarkInterner = inject[BookmarkInterner]
          val bookmark = bookmarkInterner.internBookmarks(Json.obj(
              "url" -> "http://42go.com",
              "isPrivate" -> true
            ), user, Seq(), "EMAIL").head
          bookmarkRepo.get(bookmark.id.get) === bookmark
        }
      }
    }
  }

}
