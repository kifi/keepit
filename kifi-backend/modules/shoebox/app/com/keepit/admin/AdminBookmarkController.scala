package com.keepit.controllers.admin

import scala.collection.mutable.{ HashMap => MutableMap, SynchronizedMap }
import scala.concurrent._
import scala.concurrent.duration._
import com.google.inject.Inject
import com.keepit.search.{ IndexInfo, SearchServiceClient }
import com.keepit.common.controller.{ AuthenticatedRequest, AdminController, ActionAuthenticator }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.performance._
import com.keepit.model._
import com.keepit.model.KeepSource._
import com.keepit.scraper.ScrapeScheduler
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import views.html
import com.keepit.common.db.Id
import com.keepit.common.time._
import play.api.mvc.{ AnyContent, Action }
import com.keepit.commanders.URISummaryCommander
import com.keepit.commanders.RichWhoKeptMyKeeps
import com.keepit.model.KeywordsSummary
import com.keepit.model.KeepStates

class AdminBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  scraper: ScrapeScheduler,
  searchServiceClient: SearchServiceClient,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  scrapeRepo: ScrapeInfoRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  uriSummaryCommander: URISummaryCommander,
  clock: Clock)
    extends AdminController(actionAuthenticator) {

  private def editBookmark(bookmark: Keep)(implicit request: AuthenticatedRequest[AnyContent]) = {
    db.readOnlyReplica { implicit session =>
      val uri = uriRepo.get(bookmark.uriId)
      val user = userRepo.get(bookmark.userId)
      val scrapeInfo = scrapeRepo.getByUriId(bookmark.uriId)
      val keywordsFut = uriSummaryCommander.getKeywordsSummary(uri.id.get)
      val imageUrlOptFut = uriSummaryCommander.getURIImage(uri)

      for {
        keywords <- keywordsFut
        imageUrlOpt <- imageUrlOptFut
      } yield {
        val screenshotUrl = uriSummaryCommander.getScreenshotURL(uri).getOrElse("")
        Ok(html.admin.bookmark(user, bookmark, uri, scrapeInfo, imageUrlOpt.getOrElse(""), screenshotUrl, keywords))
      }
    }
  }

  def whoKeptMyKeeps = AdminHtmlAction.authenticated { implicit request =>
    val since = clock.now.minusDays(7)
    val richWhoKeptMyKeeps = db.readOnlyReplica { implicit session =>
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
    val bookmark = db.readOnlyReplica { implicit session =>
      keepRepo.get(id)
    }
    editBookmark(bookmark)
  }

  def editFirstBookmarkForUri(id: Id[NormalizedURI]) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val bookmarkOpt = db.readOnlyReplica { implicit session =>
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
      request.body.asFormUrlEncoded.get foreach {
        case (key, values) =>
          key.split("_") match {
            case Array("private", id) => setIsPrivate(Id[Keep](id.toInt), toBoolean(values.last))
            case Array("active", id) => setIsActive(Id[Keep](id.toInt), toBoolean(values.last))
          }
      }
    }
    log.info("updating changed users")
    Redirect(request.request.referer)
  }

  def inactive(id: Id[Keep]) = AdminHtmlAction.authenticated { request =>
    db.readWrite { implicit s =>
      val keep = keepRepo.get(id)
      keepRepo.save(keep.copy(state = KeepStates.INACTIVE))
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
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
      future { timing(s"load $PAGE_SIZE bookmarks") { db.readOnlyReplica { implicit s => keepRepo.page(page, PAGE_SIZE, false, Set(KeepStates.INACTIVE)) } } } flatMap { bookmarks =>
        val usersFuture = future {
          timing("load user") {
            db.readOnlyReplica { implicit s =>
              bookmarks map (_.userId) map { id =>
                userMap.getOrElseUpdate(id, userRepo.get(id))
              }
            }
          }
        }
        val urisFuture = future {
          timing("load uris") {
            db.readOnlyReplica { implicit s =>
              bookmarks map (_.uriId) map uriRepo.get
            }
          }
        }
        val scrapesFuture = future {
          timing("load scrape info") {
            db.readOnlyReplica { implicit s =>
              bookmarks map (_.uriId) map scrapeRepo.getByUriId
            }
          }
        }

        val keywordsFut = Future.sequence(bookmarks.map { x => uriSummaryCommander.getKeywordsSummary(x.uriId) })

        for {
          users <- usersFuture
          uris <- urisFuture
          scrapes <- scrapesFuture
          keywords <- keywordsFut
        } yield (users.toList.seq, (bookmarks, uris, scrapes).zipped.toList.seq, keywords).zipped.toList.seq
      }
    }

    val bookmarkTotalCountFuture = getBookmarkCountsFuture

    val bookmarkTodayAllCountsFuture = future {
      timing("load bookmarks counts from today") {
        db.readOnlyReplica { implicit s =>
          keepRepo.getAllCountsByTimeAndSource(clock.now().minusDays(1), clock.now())
        }
      }
    }
    val privateKeeperKeepCountFuture = future {
      timing("load private keeper counts from today") {
        db.readOnlyReplica { implicit s =>
          keepRepo.getPrivateCountByTimeAndSource(clock.now().minusDays(1), clock.now(), KeepSource.keeper)
        }
      }
    }

    for {
      bookmarksAndUsers <- timing("load full bookmarksInfos") { bookmarksInfos() }
      overallCount <- bookmarkTotalCountFuture
      counts <- bookmarkTodayAllCountsFuture
      privateKeeperKeepCount <- privateKeeperKeepCountFuture
    } yield {
      val pageCount: Int = overallCount / PAGE_SIZE + 1
      val keeperKeepCount = counts.filter(_._1 == KeepSource.keeper).headOption.map(_._2)
      val total = counts.map(_._2).sum
      val tweakedCounts = counts.map {
        case cnt if cnt._1 == KeepSource.bookmarkFileImport => ("Unknown/other file import", cnt._2)
        case cnt if cnt._1 == KeepSource.bookmarkImport => ("Browser bookmark import", cnt._2)
        case cnt if cnt._1 == KeepSource.default => ("Default new user keeps", cnt._2)
        case cnt => (cnt._1.value, cnt._2)
      }.sortBy(v => -v._2)
      Ok(html.admin.bookmarks(bookmarksAndUsers, page, overallCount, pageCount, keeperKeepCount, privateKeeperKeepCount, tweakedCounts, total))
    }
  }

  private def getBookmarkCountsFuture(): Future[Int] = {
    Future.sequence(searchServiceClient.indexInfoList()).map { results =>
      var countMap = Map.empty[String, Int]
      results.flatMap(_._2).foreach { info =>
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

  def userBookmarkKeywords = AdminHtmlAction.authenticatedAsync { request =>
    val user = request.userId
    val uris = db.readOnlyReplica { implicit s =>
      keepRepo.getLatestKeepsURIByUser(user, 500, includePrivate = false)
    }.sortBy(x => x.id) // sorting helps s3 performance

    val word2vecFut = uriSummaryCommander.batchGetWord2VecKeywords(uris)

    val embedlyKeys = uris.map { uriId => uriSummaryCommander.getStoredEmbedlyKeywords(uriId) }

    val keyCounts = MutableMap.empty[String, Int].withDefaultValue(0)

    word2vecFut.map { word2vecKeys =>
      (embedlyKeys zip word2vecKeys).map {
        case (emb, w2v) =>
          val s1 = emb.map { _.name }.toSet
          val s2 = w2v.map { _.cosine }.getOrElse(Seq()).toSet
          val s3 = w2v.map { _.freq }.getOrElse(Seq()).toSet
          (s1.union(s2.intersect(s3))).foreach { word => keyCounts(word) = keyCounts(word) + 1 }
      }
      Ok(html.admin.UserKeywords(user, keyCounts.toArray.sortBy(-1 * _._2).take(100)))
    }
  }

  def bookmarksKeywordsPageView(page: Int = 0) = AdminHtmlAction.authenticatedAsync { implicit request =>
    val PAGE_SIZE = 25

    val bmsFut = future { db.readOnlyReplica { implicit s => keepRepo.page(page, PAGE_SIZE, false, Set(KeepStates.INACTIVE)) } }
    val bookmarkTotalCountFuture = getBookmarkCountsFuture()

    bmsFut.flatMap { bms =>
      val uris = bms.map { _.uriId }
      val keywordsFut = Future.sequence(uris.map { uri => uriSummaryCommander.getKeywordsSummary(uri) })

      for {
        keywords <- keywordsFut
        totalCount <- bookmarkTotalCountFuture
      } yield {
        val pageCount = (totalCount * 1f / PAGE_SIZE).ceil.toInt
        Ok(html.admin.bookmarkKeywords(bms, keywords, page, pageCount))
      }
    }
  }
}

