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
import com.keepit.test.EmptyApplication

@RunWith(classOf[JUnitRunner])
class NormalizedURITest extends SpecificationWithJUnit {

  def setup() = {
    CX.withConnection { implicit c =>
      val user1 = User(firstName = "Joe", lastName = "Smith").save
      val user2 = User(firstName = "Moo", lastName = "Brown").save
      val uri1 = NormalizedURI(title = "short title", url = "http://www.keepit.com/short").save
      val uri2 = NormalizedURI(title = "long title", url = "http://www.keepit.com/long").save
      Bookmark(userId = user1.id, title = "my title is short", url = "http://www.keepit.com/short?track=foo", uriId = uri1.id.get, source = BookmarkSource("NA")).save
      Bookmark(userId = user1.id, title = "my title is long", url = "http://www.keepit.com/long?track=bar", uriId = uri2.id.get, source = BookmarkSource("NA")).save
      Bookmark(userId = user2.id, title = "my title is long", url = "http://www.keepit.com/long?track=bar", uriId = uri2.id.get, source = BookmarkSource("NA")).save
    }
  }


  "get by state" should {
    "search gets nothing" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          var uris = NormalizedURI.getByState(NormalizedURI.States.ACTIVE)
          uris.size === 2
          uris(0).withState(NormalizedURI.States.INACTIVE).save
          NormalizedURI.getByState(NormalizedURI.States.ACTIVE).size === 1
          uris(1).withState(NormalizedURI.States.INACTIVE).save
          NormalizedURI.getByState(NormalizedURI.States.ACTIVE).size === 0
          NormalizedURI.getByState(NormalizedURI.States.INACTIVE).size === 2
          NormalizedURI.getByState(NormalizedURI.States.INACTIVE, 1).size === 1
          NormalizedURI.getByState(NormalizedURI.States.INACTIVE, 0).size === 2
          NormalizedURI.getByState(NormalizedURI.States.INACTIVE, -1).size === 2
        }
      }
    }
  }  
  
  "NormalizedURIs search by url" should {
    "search gets nothing" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          NormalizedURI.getByNormalizedUrl("http://www.keepit.com/med") === None
        }
      }
    }
    "search gets short" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          val all = NormalizedURI.all
          all.size === 2
          println(all.mkString("\n"))
          NormalizedURI.getByNormalizedUrl("http://www.keepit.com/short").get.url === "http://www.keepit.com/short" 
        }
      }
    }
    "search gets long" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          NormalizedURI.getByNormalizedUrl("http://www.keepit.com/long").get.url === "http://www.keepit.com/long" 
        }
      }
    }
  }  
  
  "NormalizedURIs get created url" should {
    "search gets nothing" in {
      running(new EmptyApplication()) {
    	CX.withConnection { implicit c =>
    	  val user1 = User(firstName = "Joe", lastName = "Smith").save
    	  val user2 = User(firstName = "Moo", lastName = "Brown").save
    	  val uri1 = NormalizedURI(title = "short title", url = "http://www.keepit.com/short", state = NormalizedURI.States.INACTIVE).save
          val uri2 = NormalizedURI(title = "long title", url = "http://www.keepit.com/long", state = NormalizedURI.States.SCRAPED).save
    	}
        CX.withConnection { implicit c =>
          NormalizedURI.getByState(NormalizedURI.States.ACTIVE).isEmpty === true
        }
      }
    }
    "search gets short" in {
      running(new EmptyApplication()) {
    	CX.withConnection { implicit c =>
    	  val user1 = User(firstName = "Joe", lastName = "Smith").save
    	  val user2 = User(firstName = "Moo", lastName = "Brown").save
    	  val uri1 = NormalizedURI(title = "one title", url = "http://www.keepit.com/one", state = NormalizedURI.States.ACTIVE).save
          val uri2 = NormalizedURI(title = "two title", url = "http://www.keepit.com/two", state = NormalizedURI.States.SCRAPED).save
          val uri3 = NormalizedURI(title = "three title", url = "http://www.keepit.com/three", state = NormalizedURI.States.ACTIVE).save
    	}
        CX.withConnection { implicit c =>
          var all = NormalizedURI.getByState(NormalizedURI.States.ACTIVE)
          println(all.mkString("\n"))
          all.size === 2
        }
      }
    }
  }  
  
}
