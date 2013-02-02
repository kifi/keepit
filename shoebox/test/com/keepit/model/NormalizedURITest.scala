package com.keepit.model

import java.sql.Connection
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import ru.circumflex.orm._
import com.keepit.controllers._
import com.keepit.common.db.{CX, State}
import com.keepit.common.db.CX._
import com.keepit.test._

@RunWith(classOf[JUnitRunner])
class NormalizedURITest extends SpecificationWithJUnit with DbRepos{

  def setup() = {
    CX.withConnection { implicit c =>
      NormalizedURICxRepo.all.size === 0 //making sure the db is clean, we had some strange failures
      UserCxRepo.all.size === 0 //making sure the db is clean
      val user1 = User(firstName = "Joe", lastName = "Smith").save
      val user2 = User(firstName = "Moo", lastName = "Brown").save
      val uri1 = createUri(title = "short title", url = "http://www.keepit.com/short")
      val uri2 = createUri(title = "long title", url = "http://www.keepit.com/long")
      val url1 = URLFactory(url = uri1.url, normalizedUriId = uri1.id.get).save
      val url2 = URLFactory(url = uri2.url, normalizedUriId = uri2.id.get).save
      BookmarkFactory(userId = user1.id.get, title = "my title is short", url = url1, uriId = uri1.id.get, source = BookmarkSource("NA")).save
      BookmarkFactory(userId = user1.id.get, title = "my title is long", url = url2, uriId = uri2.id.get, source = BookmarkSource("NA")).save
      BookmarkFactory(userId = user2.id.get, title = "my title is long", url = url2, uriId = uri2.id.get, source = BookmarkSource("NA")).save
    }
  }

  "bookmark pagination" should {
    "get all" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          BookmarkCxRepo.page(0, 10).size === 3
        }
      }
    }
    "get first" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          BookmarkCxRepo.page(0, 2).size === 2
        }
      }
    }
    "get last" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          BookmarkCxRepo.page(1, 2).size === 1
        }
      }
    }
    "get none" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          BookmarkCxRepo.page(2, 2).size === 0
        }
      }
    }
  }

  "get by state" should {
    "search gets nothing" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          var uris = NormalizedURICxRepo.getByState(NormalizedURIStates.ACTIVE)
          uris.size === 2
          uris(0).withState(NormalizedURIStates.INACTIVE).save
          NormalizedURICxRepo.getByState(NormalizedURIStates.ACTIVE).size === 1
          uris(1).withState(NormalizedURIStates.INACTIVE).save
          NormalizedURICxRepo.getByState(NormalizedURIStates.ACTIVE).size === 0
          NormalizedURICxRepo.getByState(NormalizedURIStates.INACTIVE).size === 2
          NormalizedURICxRepo.getByState(NormalizedURIStates.INACTIVE, 1).size === 1
          NormalizedURICxRepo.getByState(NormalizedURIStates.INACTIVE, 0).size === 2
          NormalizedURICxRepo.getByState(NormalizedURIStates.INACTIVE, -1).size === 2
        }
      }
    }
  }

  "NormalizedURIs search by url" should {
    "search gets nothing" in {
      running(new EmptyApplication()) {
        setup()
        db.readWrite { implicit s =>
          uriRepo.getByNormalizedUrl("http://www.keepit.com/med") === None
        }
      }
    }
    "search gets short" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          val all = NormalizedURICxRepo.all
          all.size === 2
          println(all.mkString("\n"))
          NormalizedURICxRepo.getByNormalizedUrl("http://www.keepit.com/short").get.url === "http://www.keepit.com/short"
        }
      }
    }
    "search gets long" in {
      running(new EmptyApplication()) {
        setup()
        CX.withConnection { implicit c =>
          NormalizedURICxRepo.getByNormalizedUrl("http://www.keepit.com/long").get.url === "http://www.keepit.com/long"
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
      	  val uri1 = createUri(title = "short title", url = "http://www.keepit.com/short", state = NormalizedURIStates.INACTIVE)
          val uri2 = createUri(title = "long title", url = "http://www.keepit.com/long", state = NormalizedURIStates.SCRAPED)
      	}
        CX.withConnection { implicit c =>
          NormalizedURICxRepo.getByState(NormalizedURIStates.ACTIVE).isEmpty === true
        }
      }
    }
    "search gets short" in {
      running(new EmptyApplication()) {
      	CX.withConnection { implicit c =>
      	  NormalizedURICxRepo.all.size === 0 //making sure the db is clean, trying to understand some strange failures we got
      	  val user1 = User(firstName = "Joe", lastName = "Smith").save
      	  val user2 = User(firstName = "Moo", lastName = "Brown").save
      	  val uri1 = createUri(title = "one title", url = "http://www.keepit.com/one", state = NormalizedURIStates.ACTIVE)
          val uri2 = createUri(title = "two title", url = "http://www.keepit.com/two", state = NormalizedURIStates.SCRAPED)
          val uri3 = createUri(title = "three title", url = "http://www.keepit.com/three", state = NormalizedURIStates.ACTIVE)
      	}
        CX.withConnection { implicit c =>
          val all = NormalizedURICxRepo.getByState(NormalizedURIStates.ACTIVE)
          println(all.mkString("\n"))
          all.size === 2
        }
      }
    }
  }

  def createUri(title: String, url: String, state: State[NormalizedURI] = NormalizedURIStates.ACTIVE)(implicit conn: Connection) = {
    val uri = NormalizedURIFactory(title = title, url = url, state = state)
    try {
      uri.save
    } catch {
      case e =>
        println("fail to persist uri %s. Existing URIs in the db are: %s".
            format(uri, NormalizedURICxRepo.all.map(_.toString).mkString("\n")))
        throw e
    }
  }

}
