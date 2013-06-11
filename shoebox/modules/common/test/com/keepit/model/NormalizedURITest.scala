package com.keepit.model

import org.specs2.mutable._

import com.google.inject.Injector
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._

class NormalizedURITest extends Specification with TestDBRunner {

  def setup()(implicit injector: Injector) = {
    db.readWrite {implicit s =>
      uriRepo.count === 0 //making sure the db is clean, we had some strange failures
      userRepo.count === 0 //making sure the db is clean
      val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith"))
      val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown"))
      val uri1 = createUri(title = "short title", url = "http://www.keepit.com/short")
      val uri2 = createUri(title = "long title", url = "http://www.keepit.com/long")
      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      bookmarkRepo.save(BookmarkFactory(userId = user1.id.get, title = "my title is short", url = url1, uriId = uri1.id.get, source = BookmarkSource("NA")))
      bookmarkRepo.save(BookmarkFactory(userId = user1.id.get, title = "my title is long", url = url2, uriId = uri2.id.get, source = BookmarkSource("NA")))
      bookmarkRepo.save(BookmarkFactory(userId = user2.id.get, title = "my title is long", url = url2, uriId = uri2.id.get, source = BookmarkSource("NA")))
    }
  }

  "bookmark pagination" should {
    "get all" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          bookmarkRepo.page(0, 10).size === 3
        }
      }
    }
    "get first" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          bookmarkRepo.page(0, 2).size === 2
        }
      }
    }
    "get last" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          bookmarkRepo.page(1, 2).size === 1
        }
      }
    }
    "get none" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          bookmarkRepo.page(2, 2).size === 0
        }
      }
    }
  }

  "get by state" should {
    "search gets nothing" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          var uris = uriRepo.getByState(NormalizedURIStates.ACTIVE)
          uris.size === 2
          uriRepo.save(uris(0).withState(NormalizedURIStates.INACTIVE))
          uriRepo.getByState(NormalizedURIStates.ACTIVE).size === 1
          uriRepo.save(uris(1).withState(NormalizedURIStates.INACTIVE))
          uriRepo.getByState(NormalizedURIStates.ACTIVE).size === 0
          uriRepo.getByState(NormalizedURIStates.INACTIVE).size === 2
          uriRepo.getByState(NormalizedURIStates.INACTIVE, 1).size === 1
          uriRepo.getByState(NormalizedURIStates.INACTIVE, 0).size === 2
          uriRepo.getByState(NormalizedURIStates.INACTIVE, -1).size === 2
        }
      }
    }
  }

  "NormalizedURIs search by url" should {
    "search gets nothing" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          uriRepo.getByNormalizedUrl("http://www.keepit.com/med") === None
        }
      }
    }
    "search gets short" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          val all = uriRepo.all
          all.size === 2
          println(all.mkString("\n"))
          uriRepo.getByNormalizedUrl("http://www.keepit.com/short").get.url === "http://www.keepit.com/short"
        }
      }
    }
    "search gets long" in {
      withDB() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          uriRepo.getByNormalizedUrl("http://www.keepit.com/long").get.url === "http://www.keepit.com/long"
        }
      }
    }
  }

  "NormalizedURIs get created url" should {
    "search gets nothing" in {
      withDB() { implicit injector =>
        db.readWrite { implicit s =>
      	  val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith"))
      	  val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown"))
      	  val uri1 = createUri(title = "short title", url = "http://www.keepit.com/short", state = NormalizedURIStates.INACTIVE)
          val uri2 = createUri(title = "long title", url = "http://www.keepit.com/long", state = NormalizedURIStates.SCRAPED)
      	}
        db.readWrite { implicit s =>
          uriRepo.getByState(NormalizedURIStates.ACTIVE).isEmpty === true
        }
      }
    }
    "search gets short" in {
      withDB() { implicit injector =>
        db.readWrite { implicit s =>
      	  uriRepo.all.size === 0 //making sure the db is clean, trying to understand some strange failures we got
      	  val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith"))
      	  val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown"))
      	  val uri1 = createUri(title = "one title", url = "http://www.keepit.com/one", state = NormalizedURIStates.ACTIVE)
          val uri2 = createUri(title = "two title", url = "http://www.keepit.com/two", state = NormalizedURIStates.SCRAPED)
          val uri3 = createUri(title = "three title", url = "http://www.keepit.com/three", state = NormalizedURIStates.ACTIVE)
      	}
        db.readWrite { implicit s =>
          val all = uriRepo.getByState(NormalizedURIStates.ACTIVE)
          println(all.mkString("\n"))
          all.size === 2
        }
      }
    }
  }

  def createUri(title: String, url: String, state: State[NormalizedURI] = NormalizedURIStates.ACTIVE)(implicit
      session: RWSession, injector: Injector) = {
    val uri = NormalizedURIFactory(title = title, url = url, state = state)
    try {
      uriRepo.save(uri)
    } catch {
      case e: Throwable =>
        println("fail to persist uri %s. Existing URIs in the db are: %s".
            format(uri, uriRepo.all.map(_.toString).mkString("\n")))
        throw e
    }
  }

}
