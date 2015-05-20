package com.keepit.model

import org.specs2.mutable._
import com.google.inject.Injector

import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import com.keepit.common.time.DEFAULT_DATE_TIME_ZONE

import org.joda.time.DateTime

class NormalizedURIRepoTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    db.readWrite { implicit s =>
      uriRepo.count === 0 //making sure the db is clean, we had some strange failures
      userRepo.count === 0 //making sure the db is clean
      val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
      val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown", username = Username("moo"), normalizedUsername = "moo"))
      val uri1 = createUri(title = "short title", url = "http://www.keepit.com/short")
      uriRepo.assignSequenceNumbers(1000)
      val uri2 = createUri(title = "long title", url = "http://www.keepit.com/long")
      uriRepo.assignSequenceNumbers(1000)
      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))
      keepRepo.save(Keep(title = Some("my title is short"), userId = user1.id.get, uriId = uri2.id.get, urlId = url2.id.get, url = url2.url, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      //keepRepo.save(KeepFactory(url2.url, uri = uri2, userId = user1.id.get, title = Some("my title is long"), url = url2, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

      keepRepo.save(Keep(title = Some("my title is short"), userId = user1.id.get, uriId = uri1.id.get, urlId = url1.id.get, url = url1.url, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      //keepRepo.save(KeepFactory(url1.url, uri = uri1, userId = user1.id.get, title = Some("my title is short"), url = url1, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

      keepRepo.save(Keep(title = Some("my title is long"), userId = user2.id.get, uriId = uri2.id.get, urlId = url2.id.get, url = url2.url, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
      //keepRepo.save(KeepFactory(url2.url, uri = uri2, userId = user2.id.get, title = Some("my title is long"), url = url2, source = KeepSource("NA"), visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

    }
  }

  "bookmark pagination" should {
    "get all" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          keepRepo.page(0, 10).size === 3
        }
      }
    }
    "get first" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          keepRepo.page(0, 2).size === 2
        }
      }
    }
    "get last" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          keepRepo.page(1, 2).size === 1
        }
      }
    }
    "get none" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          keepRepo.page(2, 2).size === 0
        }
      }
    }
  }

  "get by state" should {
    "search gets nothing" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          val uris = uriRepo.getByState(NormalizedURIStates.ACTIVE)
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

  "getByUri" should {

    "hash works fine" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          normalizedURIInterner.getByUri("http://www.keepit.com/short").get.url === "http://www.keepit.com/short"
          normalizedURIInterner.getByUri("http://www.keepit.com/short#lulu").get.url === "http://www.keepit.com/short"
          normalizedURIInterner.getByUri("http://www.keepit.com/none/") === None
        }
      }
    }
  }

  "NormalizedURIs search by url" should {
    "search gets nothing" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          normalizedURIInterner.getByUri("http://www.keepit.com/med") === None
        }
      }
    }
    "search gets short" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          val all = uriRepo.all
          all.size === 2
          // println(all.mkString("\n")) // can be removed?
          normalizedURIInterner.getByUri("http://www.keepit.com/short").get.url === "http://www.keepit.com/short"
        }
      }
    }
    "search gets long" in {
      withDb() { implicit injector =>
        setup()
        db.readWrite { implicit s =>
          normalizedURIInterner.getByUri("http://www.keepit.com/long").get.url === "http://www.keepit.com/long"
        }
      }
    }
  }

  "getByState" should {

    "search gets something" in {
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          uriRepo.all.size === 0 //making sure the db is clean, trying to understand some strange failures we got
          val user1 = userRepo.save(User(firstName = "Joe", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "Moo", lastName = "Brown", username = Username("moo"), normalizedUsername = "moo"))
          val uri1 = createUri(title = "one title", url = "http://www.keepit.com/one", state = NormalizedURIStates.ACTIVE)
          val uri2 = createUri(title = "two title", url = "http://www.keepit.com/two", state = NormalizedURIStates.INACTIVE)
          val uri3 = createUri(title = "three title", url = "http://www.keepit.com/three", state = NormalizedURIStates.ACTIVE)
        }
        db.readWrite { implicit s =>
          val all = uriRepo.getByState(NormalizedURIStates.ACTIVE)
          // println(all.mkString("\n")) // can be removed?
          all.size === 2
        }
      }
    }
  }

  "internByUri" should {
    "Find an existing uri without creating a new one" in {
      withDb() { implicit injector =>
        db.readOnlyMaster { implicit s =>
          uriRepo.count === 0
        }
        val xkcd = db.readWrite { implicit s =>
          uriRepo.save(NormalizedURI.withHash("http://blag.xkcd.com/2006/12/11/the-map-of-the-internet/"))
        }
        db.readWrite { implicit s =>
          uriRepo.count === 1
          normalizedURIInterner.getByUri("http://blag.xkcd.com/2006/12/11/the-map-of-the-internet/").get === xkcd
        }
        db.readWrite { implicit s =>
          normalizedURIInterner.internByUri("http://blag.xkcd.com/2006/12/11/the-map-of-the-internet/") === xkcd
        }
        db.readOnlyMaster { implicit s =>
          uriRepo.count === 1
        }
      }
    }

    "Create a uri that does not exist" in {
      withDb() { implicit injector =>
        val blowup = db.readWrite { implicit s =>
          uriRepo.count === 0
          normalizedURIInterner.getByUri("http://www.arte.tv/fr/3482046.html").isEmpty === true
          normalizedURIInterner.internByUri("http://www.arte.tv/fr/3482046.html")
        }
        db.readOnlyMaster { implicit s =>
          uriRepo.count === 1
          blowup.url === "http://www.arte.tv/fr/3482046.html"
        }
      }
    }

    "redirect works" in {
      withDb() { implicit injector =>
        val t = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val (uri0, uri1, uri2) = db.readWrite { implicit s =>
          val uri0 = createUri(title = "too_old", url = "http://www.keepit.com/too_old")
          val uri1 = createUri(title = "old", url = "http://www.keepit.com/old")
          val uri2 = createUri(title = "redirect", url = "http://www.keepit.com/redirect")
          uriRepo.save(uri0.withRedirect(uri2.id.get, t))
          uriRepo.save(uri1.withRedirect(uri2.id.get, t))
          (uri0, uri1, uri2)
        }
        db.readOnlyMaster { implicit s =>
          val updated = uriRepo.get(uri1.id.get)
          updated.redirect === uri2.id
          updated.redirectTime === Some(t)
          uriRepo.getByRedirection(uri2.id.get).map { _.title }.toSet === Set(Some("too_old"), Some("old"))
          uriRepo.getByRedirection(uri0.id.get).size === 0
        }
      }
    }

    "correctly handle bad states transition when redirect info is present" in {
      withDb() { implicit injector =>
        val uri1Redirected = db.readWrite { implicit s =>
          val t = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          val uri1 = createUri(title = "old", url = "http://www.keepit.com/old")
          val uri2 = createUri(title = "redirect", url = "http://www.keepit.com/redirect")
          uriRepo.save(uri1.withRedirect(uri2.id.get, t))
        }

        val uri1Scraped = db.readWrite { implicit s =>
          uriRepo.save(uri1Redirected.withState(NormalizedURIStates.SCRAPED))
        }

        uri1Scraped.state === NormalizedURIStates.REDIRECTED
        uri1Scraped.redirect === Some(Id[NormalizedURI](2))
      }
    }

    "can update restriction" in {
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          val uri1 = createUri(title = "old", url = "http://www.keepit.com/bad")
          uriRepo.updateURIRestriction(uri1.id.get, Some(Restriction.ADULT))
          uriRepo.get(uri1.id.get).restriction === Some(Restriction.ADULT)
          uriRepo.getRestrictedURIs(Restriction.ADULT).size === 1
          uriRepo.updateURIRestriction(uri1.id.get, None)
          uriRepo.get(uri1.id.get).restriction === None
          uriRepo.getRestrictedURIs(Restriction.ADULT).size === 0
        }
      }
    }

    "check recommendable of uris" in {
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          createUri(title = "1", url = "http://www.keepit.com/inactive")
          createUri(title = "2", url = "http://www.keepit.com/scraped", NormalizedURIStates.SCRAPED)
          val uri = createUri(title = "3", url = "http://www.keepit.com/restricted", NormalizedURIStates.SCRAPED)
          uriRepo.updateURIRestriction(uri.id.get, Some(Restriction.ADULT))
          createUri(title = "4", url = "http://www.keepit.com/good", NormalizedURIStates.SCRAPED)
          uriRepo.checkRecommendable(List(2, 3, 4, 1).map { Id[NormalizedURI](_) }) === List(true, false, true, false)
        }
      }
    }
  }

  def createUri(title: String, url: String, state: State[NormalizedURI] = NormalizedURIStates.ACTIVE)(implicit session: RWSession, injector: Injector) = {
    val uri = NormalizedURI.withHash(title = Some(title), normalizedUrl = url, state = state)
    try {
      uriRepo.save(uri)
    } catch {
      case e: Throwable =>
        // println("fail to persist uri %s. Existing URIs in the db are: %s".format(uri, uriRepo.all.map(_.toString).mkString("\n"))) // can be removed?
        throw e
    }
  }
}
