package com.keepit.model

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.inject._

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db._
import com.keepit.common.db.CX._
import com.keepit.common.time.zones.PT

import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class BookmarkTest extends SpecificationWithJUnit {

  def setup() = {
    val t1 = new DateTime(2012, 2, 14, 21, 59, 0, 0, PT)
    val t2 = new DateTime(2012, 3, 22, 14, 30, 0, 0, PT)

    CX.withConnection { implicit conn =>

      val user1 = User(firstName = "Andrew", lastName = "C", createdAt = t1).save
      val user2 = User(firstName = "Eishay", lastName = "S", createdAt = t2).save

      NormalizedURICxRepo.all.length === 0
      val uri1 = NormalizedURIFactory("Google", "http://www.google.com/").save
      val uri2 = NormalizedURIFactory("Amazon", "http://www.amazon.com/").save

      val url1 = URLFactory(url = uri1.url, normalizedUriId = uri1.id.get).save
      val url2 = URLFactory(url = uri2.url, normalizedUriId = uri2.id.get).save

      val hover = BookmarkSource("HOVER_KEEP")

      Bookmark(title = "G1", userId = user1.id.get, url = url1.url, urlId = url1.id, uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)).save
      Bookmark(title = "A1", userId = user1.id.get, url = url2.url, urlId = url2.id, uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)).save
      Bookmark(title = "G2", userId = user2.id.get, url = url1.url, urlId = url1.id, uriId = uri1.id.get, source = hover, createdAt = t2.plusDays(1)).save

      (user1, user2, uri1, uri2)
    }
  }

  "Bookmark" should {
    "load all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        val all = inject[DBConnection].readOnly(implicit session => inject[BookmarkRepo].all)
        all.map(_.title) === Seq("G1", "A1", "G2")
      }
    }
    "load by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        inject[DBConnection].readOnly{ implicit session =>
          inject[BookmarkRepo].getByUser(user1.id.get).map(_.title) === Seq("G1", "A1")
          inject[BookmarkRepo].getByUser(user2.id.get).map(_.title) === Seq("G2")
        }
      }
    }
    "load by uri" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        inject[DBConnection].readOnly{ implicit session =>
          inject[BookmarkRepo].getByUri(uri1.id.get).map(_.title) === Seq("G1", "G2")
          inject[BookmarkRepo].getByUri(uri2.id.get).map(_.title) === Seq("A1")
        }
      }
    }
    "count all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        inject[DBConnection].readOnly{ implicit session =>
          inject[BookmarkRepo].count(session) === 3
        }
      }
    }
    "count by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        inject[DBConnection].readOnly{ implicit session =>
          inject[BookmarkRepo].count(user1) === 2
          inject[BookmarkRepo].count(user2) === 1
        }
      }
    }
  }

}
