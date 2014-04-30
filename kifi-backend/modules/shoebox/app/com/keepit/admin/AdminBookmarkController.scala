package com.keepit.controllers.admin

import scala.collection.mutable.{HashMap => MutableMap, SynchronizedMap}
import scala.concurrent._
import scala.concurrent.duration._
import com.google.inject.Inject

import com.keepit.search.{IndexInfo, SearchServiceClient}
import com.keepit.common.controller.{AuthenticatedRequest, AdminController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.performance._
import com.keepit.model._
import com.keepit.model.KeepSource._
import com.keepit.scraper.ScrapeSchedulerPlugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import views.html
import com.keepit.common.db.Id
import com.keepit.common.time._
import play.api.mvc.{AnyContent, Action}
import com.keepit.commanders.URISummaryCommander
import com.keepit.commanders.RichWhoKeptMyKeeps

class AdminBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScrapeSchedulerPlugin,
  searchServiceClient: SearchServiceClient,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  scrapeRepo: ScrapeInfoRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  uriSummaryCommander: URISummaryCommander,
  clock: Clock)
    extends AdminController(actionAuthenticator) {

  implicit val dbMasterSlave = Database.Slave

  private def editBookmark(bookmark: Keep)(implicit request: AuthenticatedRequest[AnyContent]) = {
    db.readOnly { implicit session =>
      val uri = uriRepo.get(bookmark.uriId)
      val user = userRepo.get(bookmark.userId)
      val scrapeInfo = scrapeRepo.getByUriId(bookmark.uriId)
      uriSummaryCommander.getURIImage(uri) map { imageUrlOpt =>
        val screenshotUrl = uriSummaryCommander.getScreenshotURL(uri).getOrElse("")
        Ok(html.admin.bookmark(user, bookmark, uri, scrapeInfo, imageUrlOpt.getOrElse(""), screenshotUrl))
      }
    }
  }

  def whoKeptMyKeeps = AdminHtmlAction.authenticated { implicit request =>
    val since = clock.now.minusDays(7)
    val richWhoKeptMyKeeps = db.readOnly { implicit session =>
      var maxUsers = 30
      var whoKeptMyKeeps = keepRepo.whoKeptMyKeeps(request.userId, since, maxUsers)

      while (whoKeptMyKeeps.size > 15) {
        //trimming the last article and removing most popular articles
        maxUsers = maxUsers - 2
        whoKeptMyKeeps = whoKeptMyKeeps.filterNot(_.users.size > maxUsers)
        if (whoKeptMyKeeps.size > 15) {
          whoKeptMyKeeps = whoKeptMyKeeps.take(whoKeptMyKeeps.size - 2)
        }
      }
      whoKeptMyKeeps map { whoKeptMyKeep =>
        RichWhoKeptMyKeeps(whoKeptMyKeep.count, whoKeptMyKeep.latestKeep,
          uriRepo.get(whoKeptMyKeep.uri), whoKeptMyKeep.users map userRepo.get)
      }
    }
    val users: Set[User] = richWhoKeptMyKeeps.map(_.users).flatten.toSet
    Ok(html.admin.whoKeptMyKeeps(richWhoKeptMyKeeps, since, users.size))
  }


  def edit(id: Id[Keep]) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val bookmark = db.readOnly { implicit session =>
      keepRepo.get(id)
    }
    editBookmark(bookmark)
  }

  def editFirstBookmarkForUri(id: Id[NormalizedURI]) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val bookmarkOpt = db.readOnly { implicit session =>
      keepRepo.getByUri(id).headOption
    }
    bookmarkOpt match {
      case Some(bookmark) => editBookmark(bookmark)
      case None => Future.successful(NotFound(s"No bookmark for id $id"))
    }
  }

  def rescrape = AdminJsonAction.authenticatedParseJson { request =>
    val id = Id[Keep]((request.body \ "id").as[Int])
    db.readWrite { implicit session =>
      val bookmark = keepRepo.get(id)
      val uri = uriRepo.get(bookmark.uriId)
      scraper.scheduleScrape(uri)
      Ok(JsObject(Seq("status" -> JsString("ok"))))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminHtmlAction.authenticated { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Keep], isPrivate: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = keepRepo.get(id)
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      keepRepo.save(bookmark.withPrivate(isPrivate))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    def setIsActive(id: Id[Keep], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = keepRepo.get(id)
      log.info("updating bookmark %s with active = %s".format(bookmark, isActive))
      keepRepo.save(bookmark.withActive(isActive))
      log.info("updated bookmark %s".format(bookmark))
      bookmark.userId
    }

    db.readWrite { implicit s =>
      request.body.asFormUrlEncoded.get foreach { case (key, values) =>
        key.split("_") match {
          case Array("private", id) => setIsPrivate(Id[Keep](id.toInt), toBoolean(values.last))
          case Array("active", id) => setIsActive(Id[Keep](id.toInt), toBoolean(values.last))
        }
      }
    }
    log.info("updating changed users")
    Redirect(request.request.referer)
  }

  //this is an admin only task!!!
  def delete(id: Id[Keep]) = AdminHtmlAction.authenticated { request =>
    db.readWrite { implicit s =>
      keepRepo.delete(id)
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
  }

  def bookmarksView(page: Int = 0) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val PAGE_SIZE = 25

    val userMap = new MutableMap[Id[User], User] with SynchronizedMap[Id[User], User]

    def bookmarksInfos() = {
      future { timing(s"load $PAGE_SIZE bookmarks") { db.readOnly { implicit s => keepRepo.page(page, PAGE_SIZE, false, Set(KeepStates.INACTIVE)) } } } flatMap { bookmarks =>
        val usersFuture = future { timing("load user") { db.readOnly { implicit s =>
          bookmarks map (_.userId) map { id =>
            userMap.getOrElseUpdate(id, userRepo.get(id))
          }
        }}}
        val urisFuture = future { timing("load uris") { db.readOnly { implicit s =>
          bookmarks map (_.uriId) map uriRepo.get
        }}}
        val scrapesFuture = future { timing("load scrape info") { db.readOnly { implicit s =>
          bookmarks map (_.uriId) map scrapeRepo.getByUriId
        }}}

        for {
          users <- usersFuture
          uris <- urisFuture
          scrapes <- scrapesFuture
        } yield (users.toList.seq, (bookmarks, uris, scrapes).zipped.toList.seq).zipped.toList.seq
      }
    }

    val bookmarkTotalCountFuture = {
      Future.sequence(searchServiceClient.indexInfoList()).map{ results =>
        var countMap = Map.empty[String, Int]
        results.flatMap(_._2).foreach{ info =>
          if (info.name.startsWith("BookmarkStore")) {
            countMap.get(info.name) match {
              case Some(count) if count >= info.numDocs =>
              case _ => countMap += (info.name -> info.numDocs)
            }
          }
        }
        countMap.values.sum
      }
    }

    val bookmarkTodayImportedCountFuture = future { timing("load bookmarks import counts from today") { db.readOnly { implicit s =>
      keepRepo.getCountByTimeAndSource(clock.now().toDateTime(zones.PT).withTimeAtStartOfDay().toDateTime(zones.UTC), clock.now(), KeepSource.bookmarkImport)
    }}}
    val bookmarkTodayOthersCountFuture = future { timing("load bookmarks other counts from today") { db.readOnly { implicit s =>
      keepRepo.getCountByTime(clock.now().toDateTime(zones.PT).withTimeAtStartOfDay().toDateTime(zones.UTC), clock.now())
    }}}


    for {
      bookmarksAndUsers <- timing("load full bookmarksInfos") { bookmarksInfos() }
      count <- bookmarkTotalCountFuture
      todayImportedCount <- bookmarkTodayImportedCountFuture
      todayOthersCount <- bookmarkTodayOthersCountFuture
    } yield {
      val pageCount: Int = count / PAGE_SIZE + 1
      Ok(html.admin.bookmarks(bookmarksAndUsers, page, count, pageCount, todayImportedCount, todayOthersCount))
    }

  }
}

