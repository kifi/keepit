package com.keepit.model

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.common.time.zones.PT
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class BookmarkTest extends SpecificationWithJUnit {

  def setup() = {
    CX.withConnection { implicit conn =>
      val t1 = new DateTime(2012, 2, 14, 21, 59, 0, 0, PT)
      val t2 = new DateTime(2012, 3, 22, 14, 30, 0, 0, PT)

      val user1 = User(firstName = "Andrew", lastName = "C", createdAt = t1).save
      val user2 = User(firstName = "Eishay", lastName = "S", createdAt = t2).save

      NormalizedURI.all.length === 0
      val uri1 = NormalizedURI("Google", "http://www.google.com/").save
      val uri2 = NormalizedURI("Amazon", "http://www.amazon.com/").save

      val hover = BookmarkSource("HOVER_KEEP")

      Bookmark(title = "G1", userId = user1.id.get, metadata = Some(NormalizedURIMetadata(uri1.url, "", uri1.id.get)), uriId = uri1.id.get, source = hover, createdAt = t1.plusMinutes(3)).save
      Bookmark(title = "A1", userId = user1.id.get, metadata = Some(NormalizedURIMetadata(uri2.url, "", uri2.id.get)), uriId = uri2.id.get, source = hover, createdAt = t1.plusHours(50)).save
      Bookmark(title = "G2", userId = user2.id.get, metadata = Some(NormalizedURIMetadata(uri1.url, "", uri1.id.get)), uriId = uri1.id.get, source = hover, createdAt = t2.plusDays(1)).save

      (user1, user2, uri1, uri2)
    }
  }

  "Bookmark" should {
    "load all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          Bookmark.all.map(_.title) === Seq("G1", "A1", "G2")
        }
      }
    }
    "create metadata when it doesn't previously exist" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          val meta = Bookmark.ofUri(uri1).head.metadata.get
          meta.originalUrl === uri1.url
          meta.history.size === 1
        }
      }
    }
    "load by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          Bookmark.ofUser(user1).map(_.title) === Seq("G1", "A1")
          Bookmark.ofUser(user2).map(_.title) === Seq("G2")
        }
      }
    }
    "load by uri" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          Bookmark.ofUri(uri1).map(_.title) === Seq("G1", "G2")
          Bookmark.ofUri(uri2).map(_.title) === Seq("A1")
        }
      }
    }
    "count all" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          Bookmark.count === 3
        }
      }
    }
    "count by user" in {
      running(new EmptyApplication()) {
        val (user1, user2, uri1, uri2) = setup()
        CX.withConnection { implicit conn =>
          Bookmark.count(user1) === 2
          Bookmark.count(user2) === 1
        }
      }
    }
    "get daily keeps" in {
      running(new EmptyApplication()) {
        CX.withConnection { implicit conn =>
          val (user1, user2, uri1, uri2) = setup()
          Bookmark.getDailyKeeps === Map(
              user1.id.get -> Map(0 -> 1, 2 -> 1),
              user2.id.get -> Map(1 -> 1))
        }
      }
    }
  }

}
