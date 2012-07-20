package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import com.keepit.controllers._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._

@RunWith(classOf[JUnitRunner])
class BookmarkTest extends SpecificationWithJUnit {

  def setup() = {
    CX.withConnection { implicit c =>
      val user1 = User(firstName = "Joe", lastName = "Smith").save
      val user2 = User(firstName = "Moo", lastName = "Brown").save
      Bookmark(userId = user1.id, title = "my title is short", url = "http://www.keepit.com/short", urlHash = "AAA", normalizedUrl = "http://www.keepit.com/1").save
      Bookmark(userId = user1.id, title = "my title is long", url = "http://www.keepit.com/long", urlHash = "BBB", normalizedUrl = "http://www.keepit.com/2").save
      Bookmark(userId = user2.id, title = "my title is long", url = "http://www.keepit.com/long", urlHash = "BBB", normalizedUrl = "http://www.keepit.com/2").save
    }
  }
  
  "Bookmarks" should {
    "search none" in {
      running(FakeApplication()) {
        setup()
        val none = CX.withConnection { implicit c =>
          Bookmark.search("none")
        }
        none.size === 0
      }
    }
    "search short" in {
      running(FakeApplication()) {
        setup()
        val shorts = CX.withConnection { implicit c =>
          Bookmark.search("short")
        }
        shorts.size === 1
        shorts(0).bookmarks.size === 1
        shorts(0).score === 1F
      }
    }
    "search titles" in {
      running(FakeApplication()) {
        setup()
        val titles = CX.withConnection { implicit c =>
          Bookmark.search("title")
        }
        titles.size === 2
        titles(0).bookmarks.size === 1
        titles(0).score === 1F
        titles(0).bookmarks(0).urlHash === "AAA"
        titles(1).bookmarks.size === 2
        titles(1).bookmarks(0).urlHash === "BBB"
        titles(1).score === 1F
      }
    }
    "search short titles" in {
      running(FakeApplication()) {
        setup()
        val titles = CX.withConnection { implicit c =>
          Bookmark.search("short title")
        }
        titles.size === 2
        titles(0).bookmarks.size === 1
        titles(0).score === 2F
        titles(0).bookmarks(0).urlHash === "AAA"
        titles(1).bookmarks.size === 2
        titles(1).bookmarks(0).urlHash === "BBB"
        titles(1).score === 1F
      }
    }
  }

  
}
