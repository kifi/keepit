package com.keepit.model

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.inject._

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.time.zones.PT

import com.keepit.test._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class BookmarkTest extends SpecificationWithJUnit with DbRepos {

  def setup() = {
    val t1 = new DateTime(2012, 2, 14, 21, 59, 0, 0, PT)
    val t2 = new DateTime(2012, 3, 22, 14, 30, 0, 0, PT)

    db.readWrite {implicit s =>

      val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
      val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

      uriRepo.count === 0
      val uri1 = uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/"))
      val uri2 = uriRepo.save(NormalizedURIFactory("Amazon", "http://www.amazon.com/"))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

      val hover = BookmarkSource("HOVER_KEEP")

      bookmarkRepo.save(Bookmark(title = "G1", userId = user1.id.get, url = url1.url, urlId = url1.id, uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)))
      bookmarkRepo.save(Bookmark(title = "A1", userId = user1.id.get, url = url2.url, urlId = url2.id, uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)))
      bookmarkRepo.save(Bookmark(title = "G2", userId = user2.id.get, url = url1.url, urlId = url1.id, uriId = uri1.id.get, source = hover, createdAt = t2.plusDays(1)))

      (user1, user2, uri1, uri2)
    }
  }

  "Bookmark" should {
    "load all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        val cxAll = db.readOnly {implicit s =>
          bookmarkRepo.all
        }
        println(cxAll mkString "\n")
        val all = inject[DBConnection].readOnly(implicit session => bookmarkRepo.all)
        all.map(_.title) === Seq("G1", "A1", "G2")
      }
    }
    "load by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getByUser(user1.id.get).map(_.title) === Seq("G1", "A1")
          bookmarkRepo.getByUser(user2.id.get).map(_.title) === Seq("G2")
        }
      }
    }
    "load by uri" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.getByUri(uri1.id.get).map(_.title) === Seq("G1", "G2")
          bookmarkRepo.getByUri(uri2.id.get).map(_.title) === Seq("A1")
        }
      }
    }
    "count all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.count(s) === 3
        }
      }
    }
    "count by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        db.readOnly {implicit s =>
          bookmarkRepo.count(user1.id.get) === 2
          bookmarkRepo.count(user2.id.get) === 1
        }
      }
    }
  }

}
