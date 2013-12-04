package com.keepit.controllers.admin

import scala.collection.mutable.{HashMap => MutableMap, SynchronizedMap}
import scala.concurrent._
import scala.concurrent.duration._
import com.google.inject.Inject

import com.keepit.search.{IndexInfo, SearchServiceClient}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.performance._
import com.keepit.model._
import com.keepit.model.BookmarkSource._
import com.keepit.scraper.ScraperPlugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import views.html
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.db.Id

class AdminBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScraperPlugin,
  searchServiceClient: SearchServiceClient,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  scrapeRepo: ScrapeInfoRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  s3ScreenshotStore: S3ScreenshotStore)
    extends AdminController(actionAuthenticator) {

  implicit val dbMasterSlave = Database.Slave

  def edit(id: Id[Bookmark]) = AdminHtmlAction { request =>
    Async {
      db.readOnlyAsync { implicit session =>
        val bookmark = bookmarkRepo.get(id)
        val uri = uriRepo.get(bookmark.uriId)
        val user = userRepo.get(bookmark.userId)
        val scrapeInfo = scrapeRepo.getByUriId(bookmark.uriId)
        val screenshotUrl = s3ScreenshotStore.getScreenshotUrl(uri).getOrElse("")
        Ok(html.admin.bookmark(user, bookmark, uri, scrapeInfo, screenshotUrl))
      }
    }
  }

  def rescrape = AdminJsonAction { request =>
    val id = Id[Bookmark]((request.body.asJson.get \ "id").as[Int])
    db.readWrite { implicit session =>
      val bookmark = bookmarkRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      scraper.scheduleScrape(uri)
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

  def bookmarksView(page: Int = 0) = AdminHtmlAction { implicit request =>
    val PAGE_SIZE = 50

    val userMap = new MutableMap[Id[User], User] with SynchronizedMap[Id[User], User]

    def bookmarksInfos() = {
      future { time(s"load $PAGE_SIZE bookmarks") { db.readOnly { implicit s => bookmarkRepo.page(page, PAGE_SIZE, false, Set(BookmarkStates.INACTIVE)) } } } flatMap { bookmarks =>
        for {
          users <- future { time("load user") { db.readOnly { implicit s =>
            bookmarks map (_.userId) map { id =>
              userMap.getOrElseUpdate(id, userRepo.get(id))
            }
          }}}
          uris <- future { time("load uris") { db.readOnly { implicit s =>
            bookmarks map (_.uriId) map uriRepo.get
          }}}
          scrapes <- future { time("load scrape info") { db.readOnly { implicit s =>
            bookmarks map (_.uriId) map scrapeRepo.getByUriId
          }}}
        } yield (users.toList.seq, (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq
      }
    }

    val (count, bookmarksAndUsers) = Await.result(for {
        bookmarksAndUsers <- time("load full bookmarksInfos") { bookmarksInfos() }
        count <- searchServiceClient.uriGraphIndexInfo() map { infos =>
          (infos find (_.name == "BookmarkStore")).get.numDocs
        }
      } yield (count, bookmarksAndUsers), 1 minutes)

    val pageCount: Int = count / PAGE_SIZE + 1
    Ok(html.admin.bookmarks(bookmarksAndUsers, page, count, pageCount))
  }
}

