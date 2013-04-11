package com.keepit.controllers.admin

import com.keepit.classify.{Domain, DomainClassifier, DomainRepo}
import com.keepit.common.analytics.EventFamilies
import com.keepit.common.analytics.Events
import com.keepit.common.performance._
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
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
import scala.concurrent._
import ExecutionContext.Implicits.global

import scala.collection.mutable.{HashMap => MutableMap, SynchronizedMap}

import com.keepit.common.analytics.ActivityStream

import com.google.inject.{Inject, Singleton}

import views.html

@Singleton
class AdminBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScraperPlugin,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  socialRepo: UserWithSocialRepo,
  userRepo: UserRepo,
  scrapeRepo: ScrapeInfoRepo,
  socialUserInfoRepo: SocialUserInfoRepo)
    extends AdminController(actionAuthenticator) {

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    Async {
      db.readOnlyAsync { implicit session =>
        val bookmark = bookmarkRepo.get(id)
        val uri = uriRepo.get(bookmark.uriId)
        val user = socialRepo.toUserWithSocial(userRepo.get(bookmark.userId))
        val scrapeInfo = scrapeRepo.getByUri(bookmark.uriId)
        Ok(html.admin.bookmark(user, bookmark, uri, scrapeInfo))
      }
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

    db.readWrite { implicit s =>
      request.body.asFormUrlEncoded.get foreach { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => setIsPrivate(Id[Bookmark](id.toInt), toBoolean(values.last))
          case Array("active", id) => setIsActive(Id[Bookmark](id.toInt), toBoolean(values.last))
        }
      }
    }
    log.info("updating changed users")
    Redirect(request.request.referer)
  }

  //this is an admin only task!!!
  def delete(id: Id[Bookmark]) = AdminHtmlAction { request =>
    db.readWrite { implicit s =>
      bookmarkRepo.delete(id)
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
  }

  def all = AdminHtmlAction { request =>
    val bookmarks = db.readOnly(implicit session => bookmarkRepo.all)
    Ok(JsArray(bookmarks map BookmarkSerializer.bookmarkSerializer.writes _))
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction { request =>
    val PAGE_SIZE = 50

    val userMap = new MutableMap[Id[User], User] with SynchronizedMap[Id[User], User]
    val socialUserInfoMap = new MutableMap[Id[User], SocialUserInfo] with SynchronizedMap[Id[User], SocialUserInfo]

    def bookmarksInfos() = {
      future { time(s"load $PAGE_SIZE bookmarks") { db.readOnly { implicit s => bookmarkRepo.page(page, PAGE_SIZE) } } } flatMap { bookmarks =>
        for {
          users <- future { time("load user") { db.readOnly { implicit s =>
            bookmarks map (_.userId) map { id =>
              userMap.getOrElseUpdate(id, userRepo.get(id))
            }
          }}}
          socialUserInfo <- future { time("load socialUserInfo") { db.readOnly { implicit s =>
            bookmarks map (_.userId) map { id =>
              socialUserInfoMap.getOrElseUpdate(id, socialUserInfoRepo.getByUser(id).head)
            }
          }}}
          uris <- future { time("load uris") { db.readOnly { implicit s =>
            bookmarks map (_.uriId) map uriRepo.get
          }}}
          scrapes <- future { time("load scrape info") { db.readOnly { implicit s =>
            bookmarks map (_.uriId) map scrapeRepo.getByUri
          }}}
        } yield ((users, socialUserInfo).zipped.toList.seq,
                 (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq
      }
    }

    val (count, bookmarksAndUsers) = Await.result( for {
        bookmarksAndUsers <- time("load full bookmarksInfos") { bookmarksInfos() }
        count <- future { time("count bookmarks") { db.readOnly { implicit s => bookmarkRepo.count(s) } } }
      } yield (count, bookmarksAndUsers), 1 minutes)

    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(html.admin.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }
}

