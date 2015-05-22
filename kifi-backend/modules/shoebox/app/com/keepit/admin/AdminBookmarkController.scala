package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.performance._
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.model.{ KeepStates, _ }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent }
import views.html

import scala.collection.mutable
import scala.collection.mutable.{ HashMap => MutableMap }
import scala.concurrent._

class AdminBookmarksController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  libraryRepo: LibraryRepo,
  keepImageCommander: KeepImageCommander,
  keywordSummaryCommander: KeywordSummaryCommander,
  libraryCommander: LibraryCommander,
  keepCommander: KeepsCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  keepDecorator: KeepDecorator,
  clock: Clock,
  implicit val imageConfig: S3ImageConfig)
    extends AdminUserActions {

  private def editBookmark(bookmark: Keep)(implicit request: UserRequest[AnyContent]) = {
    db.readOnlyMaster { implicit session =>
      val uri = uriRepo.get(bookmark.uriId)
      val user = userRepo.get(bookmark.userId)
      val keepId = bookmark.id.get
      val keywordsFut = keywordSummaryCommander.getKeywordsSummary(bookmark.uriId)
      val imageUrlOpt = keepImageCommander.getBasicImagesForKeeps(Set(keepId)).get(keepId).flatMap(_.get(ProcessedImageSize.Large.idealSize).map(_.path.getUrl))
      val libraryOpt = bookmark.libraryId.map { opt => libraryRepo.get(opt) }

      keywordsFut.map { keywords =>
        Ok(html.admin.bookmark(user, bookmark, uri, imageUrlOpt.getOrElse(""), "", keywords, libraryOpt))
      }
    }
  }

  def disableUrl(id: Id[NormalizedURI]) = Action { implicit request =>
    val url = db.readWrite { implicit s =>
      val uri = uriRepo.get(id)
      uriRepo.save(uri.copy(state = NormalizedURIStates.INACTIVE))
      uri.url
    }
    Ok(s"disabling $url")
  }

  def whoKeptMyKeeps = AdminUserPage { implicit request =>
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

  def edit(id: Id[Keep]) = AdminUserPage.async { implicit request =>
    val bookmark = db.readOnlyReplica { implicit session =>
      keepRepo.get(id)
    }
    editBookmark(bookmark)
  }

  def editFirstBookmarkForUri(id: Id[NormalizedURI]) = AdminUserPage.async { implicit request =>
    val bookmarkOpt = db.readOnlyReplica { implicit session =>
      keepRepo.getByUri(id).headOption
    }
    bookmarkOpt match {
      case Some(bookmark) => editBookmark(bookmark)
      case None => Future.successful(NotFound(s"No bookmark for id $id"))
    }
  }

  //post request with a list of private/public and active/inactive
  def updateBookmarks() = AdminUserPage { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setIsPrivate(id: Id[Keep], isPrivate: Boolean)(implicit session: RWSession): Id[User] = {
      val bookmark = keepRepo.get(id)
      val (mainLib, secretLib) = libraryCommander.getMainAndSecretLibrariesForUser(bookmark.userId)
      def getLibFromPrivacy(isPrivate: Boolean) = {
        if (isPrivate) secretLib else mainLib
      }
      log.info("updating bookmark %s with private = %s".format(bookmark, isPrivate))
      val lib = getLibFromPrivacy(isPrivate)
      keepRepo.save(bookmark.copy(visibility = lib.visibility, libraryId = Some(lib.id.get)))
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

  def inactive(id: Id[Keep]) = AdminUserPage { request =>
    db.readWrite { implicit s =>
      val keep = keepRepo.get(id)
      keepRepo.save(keep.copy(state = KeepStates.INACTIVE))
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
  }

  //this is an admin only task!!!
  def delete(id: Id[Keep]) = AdminUserPage { request =>
    db.readWrite { implicit s =>
      keepRepo.delete(id)
      Redirect(com.keepit.controllers.admin.routes.AdminBookmarksController.bookmarksView(0))
    }
  }

  def bookmarksView(page: Int = 0) = AdminUserPage.async { implicit request =>
    val PAGE_SIZE = 25

    val userMap = mutable.Map[Id[User], User]()

    def bookmarksInfos() = {
      Future { db.readOnlyReplica { implicit s => keepRepo.page(page, PAGE_SIZE, false, Set(KeepStates.INACTIVE)) } } flatMap { bookmarks =>
        val usersFuture = Future {
          timing("load user") {
            db.readOnlyMaster { implicit s =>
              bookmarks map (_.userId) map { id =>
                userMap.getOrElseUpdate(id, userRepo.get(id))
              }
            }
          }
        }
        val urisFuture = Future {
          timing("load uris") {
            db.readOnlyReplica { implicit s =>
              bookmarks map (_.uriId) map uriRepo.get
            }
          }
        }

        for {
          users <- usersFuture
          uris <- urisFuture
        } yield (users.toList.seq, (bookmarks, uris).zipped.toList.seq).zipped.toList.seq
      }
    }

    val bookmarkTotalCountFuture = keepCommander.getKeepsCountFuture()

    val bookmarkTodayAllCountsFuture = Future {
      timing("load bookmarks counts from today") {
        db.readOnlyReplica { implicit s =>
          keepRepo.getAllCountsByTimeAndSource(clock.now().minusDays(1), clock.now())
        }
      }
    }

    val privateKeeperKeepCountFuture = Future {
      timing("load private keeper counts from today") {
        db.readOnlyReplica { implicit s =>
          keepRepo.getPrivateCountByTimeAndSource(clock.now().minusDays(1), clock.now(), KeepSource.keeper)
        }
      }
    }

    for {
      bookmarksAndUsers <- bookmarksInfos()
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

  def userBookmarkKeywords = AdminUserPage.async { implicit request =>
    val user = request.userId
    val uris = db.readOnlyReplica { implicit s =>
      keepRepo.getLatestKeepsURIByUser(user, 500, includePrivate = false)
    }.sortBy(x => x.id) // sorting helps s3 performance

    val word2vecFut = keywordSummaryCommander.batchGetWord2VecKeywords(uris)

    val embedlyKeysFut = Future.sequence(uris.map { uriId =>
      keywordSummaryCommander.getFetchedKeywords(uriId).map(_._2)
    })

    val keyCounts = MutableMap.empty[String, Int].withDefaultValue(0)

    (embedlyKeysFut zip word2vecFut).map {
      case (embedlyKeys, word2vecKeys) =>
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

  def bookmarksKeywordsPageView(page: Int = 0) = AdminUserPage.async { implicit request =>
    val PAGE_SIZE = 25

    val bmsFut = Future { db.readOnlyReplica { implicit s => keepRepo.page(page, PAGE_SIZE, false, Set(KeepStates.INACTIVE)) } }
    val bookmarkTotalCountFuture = keepCommander.getKeepsCountFuture()

    bmsFut.flatMap { bms =>
      val uris = bms.map { _.uriId }
      val keywordsFut = Future.sequence(uris.map { uri => keywordSummaryCommander.getKeywordsSummary(uri) })

      for {
        keywords <- keywordsFut
        totalCount <- bookmarkTotalCountFuture
      } yield {
        val pageCount = (totalCount * 1f / PAGE_SIZE).ceil.toInt
        Ok(html.admin.bookmarkKeywords(bms, keywords, page, pageCount))
      }
    }
  }

  def deleteTag(userId: Id[User], tagName: String) = AdminUserAction { request =>
    db.readOnlyMaster { implicit s =>
      collectionRepo.getByUserAndName(userId, Hashtag(tagName))
    } map { coll =>
      implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.unknown).build
      collectionCommander.deleteCollection(coll)
      NoContent
    } getOrElse {
      NotFound(Json.obj("error" -> "not_found"))
    }
  }

  def www$youtube$com$watch$v$otCpCn0l4Wo(keepId: Id[Keep]) = UserAction {
    db.readWrite { implicit session =>
      keepRepo.save(keepRepo.get(keepId).copy(keptAt = clock.now().plusDays(1000)))
    }
    Ok
  }

  def populateKeepNotesWithTag(page: Int = 0, size: Int = 500, grouping: Int = 500) = AdminUserAction {
    log.info(s"[Admin] populating keep notes with its tags page: $page, size: $size, offset: ${page * size}")
    db.readOnlyMaster { implicit s =>
      val keeps = keepRepo.page(page, size, Set.empty)
      log.info(s"[Admin] paging through ${keeps.length} keeps...")
      keeps
    }.grouped(grouping).foreach { keepGroup =>
      val keepIds = keepGroup.map(_.id.get).toSet
      // look for hashtags for every keep
      val keepHashtagsMap = db.readOnlyMaster { implicit s =>
        collectionRepo.getHashtagsByKeepIds(keepIds)
      }
      val keepNotesMap = keepGroup.map { k =>
        val newNote = keepHashtagsMap.get(k.id.get) match {
          case Some(tags) if tags.nonEmpty =>
            val noteStr = k.note getOrElse ""
            Some(Hashtags.addNewHashtagsToString(noteStr, tags))
          case _ =>
            k.note
        }
        (k.id.get, newNote.map(_.trim).filter(_.nonEmpty))
      }.toMap
      log.info(s"[Admin] populating ${keepGroup.length} keeps with hashtags in note field")
      db.readWrite { implicit s =>
        keepGroup.map { k =>
          val newNote = keepNotesMap(k.id.get)
          log.info(s"[Admin] updating note... new:_${newNote}_ vs. original:_${k.note}_ ${newNote != k.note}")
          if (newNote != k.note) {
            log.info(s"[Admin] really updating note... ${k.id.get}")
            val updatedK = keepRepo.save(k.copy(note = newNote))
            log.info(s"[Admin] UPDATED! ${updatedK}")
          }
        }
      }
    }
    Ok
  }

}
