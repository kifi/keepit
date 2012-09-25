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
      val uri1 = NormalizedURI(title = "short title", url = "http://www.keepit.com/title/short", urlHash = "AAA").save
      val uri2 = NormalizedURI(title = "long title", url = "http://www.keepit.com/long", urlHash = "BBB").save
      Bookmark(userId = user1.id, title = "my title is short", url = "http://www.keepit.com/short", uriId = uri1.id.get).save
      Bookmark(userId = user1.id, title = "my title is long", url = "http://www.keepit.com/long", uriId = uri2.id.get).save
      Bookmark(userId = user2.id, title = "my title is long", url = "http://www.keepit.com/long", uriId = uri2.id.get).save
    }
  }
  
  "Bookmarks" should {
    "search none" in {
      running(FakeApplication()) {
        setup()
        val none = CX.withConnection { implicit c =>
          NormalizedURI.search("none")
        }
        none.size === 0
      }
    }
    "search short" in {
      running(FakeApplication()) {
        setup()
        val shorts = CX.withConnection { implicit c =>
          NormalizedURI.search("short")
        }
        shorts.size === 1
        CX.withConnection { implicit c =>
          shorts(0).uri.bookmarks.size === 1
        }
        shorts(0).score === 2F
      }
    }
    "search titles" in {
      running(FakeApplication()) {
        setup()
        val titles = CX.withConnection { implicit c =>
          NormalizedURI.search("title")
        }
        titles.size === 2
        CX.withConnection { implicit c =>
          titles(0).uri.bookmarks.size === 1
        }
        titles(0).score === 2F
        titles(0).uri.urlHash === "AAA"
        CX.withConnection { implicit c =>
          titles(1).uri.bookmarks.size === 2
        }
        titles(1).uri.urlHash === "BBB"
        titles(1).score === 1F
      }
    }
    "search short titles" in {
      running(FakeApplication()) {
        setup()
        val titles = CX.withConnection { implicit c =>
          NormalizedURI.search("short title")
        }
        titles.size === 2
        CX.withConnection { implicit c =>
          titles(0).uri.bookmarks.size === 1
        }
        titles(0).score === 2F
        titles(0).uri.urlHash === "AAA"
        CX.withConnection { implicit c =>
          titles(1).uri.bookmarks.size === 2
        }
        titles(1).uri.urlHash === "BBB"
        titles(1).score === 1F
      }
    }
  }
}
