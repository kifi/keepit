package com.keepit.controllers.admin

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.async._
import com.keepit.common.controller.AdminController
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.net._
import com.keepit.common.social._
import com.keepit.model._
import com.keepit.scraper.ScraperPlugin
import com.keepit.search.graph.URIGraph
import com.keepit.search.graph.URIGraphPlugin
import com.keepit.serializer.BookmarkSerializer

import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.json._
import scala.concurrent.duration._

import com.keepit.common.analytics.ActivityStream

import com.google.inject.{Inject, Singleton}

import views.html
import views.html

@Singleton
class AdminBookmarksController @Inject() (db: Database, scraper: ScraperPlugin, uriGraphPlugin: URIGraphPlugin,
  bookmarkRepo: BookmarkRepo, uriRepo: NormalizedURIRepo, socialRepo: UserWithSocialRepo, userRepo: UserRepo,scrapeRepo: ScrapeInfoRepo)
    extends AdminController {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    db.readOnly { implicit session =>
      val bookmark = bookmarkRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      val user = socialRepo.toUserWithSocial(userRepo.get(bookmark.userId))
      val scrapeInfo = scrapeRepo.getByUri(bookmark.uriId)
      Ok(html.admin.bookmark(user, bookmark, uri, scrapeInfo))
    }
  }

  def rescrape = AdminJsonAction { request =>
    val id = Id[Bookmark]((request.body.asJson.get \ "id").as[Int])
    db.readOnly { implicit session =>
      val bookmark = bookmarkRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      Await.result(scraper.asyncScrape(uri), 1 minutes)
      Ok(JsObject(Seq("status" -> JsString("ok"))))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Bookmark], isPrivate: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = bookmarkRepo.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      bookmarkRepo.save(bookmark.withPrivate(isPrivate))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Bookmark], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = bookmarkRepo.get(id)
      log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
      bookmarkRepo.save(bookmark.withActive(isActive))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    val uniqueUsers = db.readWrite { implicit s =>
      val modifiedUserIds = request.body.asFormUrlEncoded.get map { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => setIsPrivate(Id[Bookmark](id.toInt), toBoolean(values.last))
          case Array("active", id) => setIsActive(Id[Bookmark](id.toInt), toBoolean(values.last))
        }
      }
      Set(modifiedUserIds.toSeq: _*)
    }
    uniqueUsers foreach { userId =>
      log.info("updating user %s".format(userId))
      uriGraphPlugin.update(userId)
    }
    Redirect(request.request.referer)
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = AdminHtmlAction { request =>
    db.readWrite { implicit s =>
      val bookmark = bookmarkRepo.get(id)
      bookmarkRepo.delete(id)
      uriGraphPlugin.update(bookmark.userId)
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = db.readOnly(implicit session => bookmarkRepo.all)
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 200
    val (count, bookmarksAndUsers) = db.readOnly { implicit s =>
      val bookmarks = bookmarkRepo.page(page, PAGE_SIZE)
      val users = bookmarks map (_.userId) map userRepo.get map socialRepo.toUserWithSocial
      val uris = bookmarks map (_.uriId) map uriRepo.get map (bookmarkRepo.uriStats)
      val scrapes = bookmarks map (_.uriId) map scrapeRepo.getByUri
      val count = bookmarkRepo.count(s)
      (count, (users, (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq)
    }
    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(html.admin.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }
}

