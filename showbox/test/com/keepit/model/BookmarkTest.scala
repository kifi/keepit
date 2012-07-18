package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._

@RunWith(classOf[JUnitRunner])
class BookmarkTest extends SpecificationWithJUnit {
  
  "Bookmarks" should {
    "search" in {
      running(FakeApplication()) {
        val bookmarks = CX.withConnection { implicit c =>
          val user = User(firstName = "Joe", lastName = "Smith").save
          Bookmark(userId = user.id, title = "my title is short", url = "http://www.keepit.com/short", urlHash = "AAA", normalizedUrl = "http://www.keepit.com/1").save ::
          Bookmark(userId = user.id, title = "my title is long", url = "http://www.keepit.com/long", urlHash = "BBB", normalizedUrl = "http://www.keepit.com/1").save ::
          Nil
        }
        val shorts = CX.withConnection { implicit c =>
          Bookmark.search("short")
        }
        shorts.size === 1
      }
    }
  }

  
}
