package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ChunkedResponseHelper
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.net._
import com.keepit.common.performance._
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.heimdal._
import com.keepit.integrity.LibraryChecker
import com.keepit.model.{ KeepStates, _ }
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.social.twitter.RawTweet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent }
import views.html
import com.keepit.common.core._

import scala.collection.mutable
import scala.collection.mutable.{ HashMap => MutableMap }
import scala.concurrent._
import scala.util.{ Try, Success }

class AdminBookmarksController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  uriRepo: NormalizedURIRepo,
  userRepo: UserRepo,
  libraryRepo: LibraryRepo,
  ktlRepo: KeepToLibraryRepo,
  ktuRepo: KeepToUserRepo,
  keepImageCommander: KeepImageCommander,
  keywordSummaryCommander: KeywordSummaryCommander,
  keepCommander: KeepCommander,
  collectionCommander: CollectionCommander,
  collectionRepo: CollectionRepo,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  libraryChecker: LibraryChecker,
  clock: Clock,
  keepToCollectionRepo: KeepToCollectionRepo,
  rawKeepRepo: RawKeepRepo,
  sourceRepo: KeepSourceAttributionRepo,
  uriInterner: NormalizedURIInterner,
  implicit val imageConfig: S3ImageConfig)
    extends AdminUserActions {

  private def editBookmark(bookmark: Keep)(implicit request: UserRequest[AnyContent]) = {
    db.readOnlyMaster { implicit session =>
      val uri = uriRepo.get(bookmark.uriId)
      val user = bookmark.userId.map(userRepo.get)
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

  def inactive(id: Id[Keep]) = AdminUserPage { request =>
    db.readWrite { implicit s =>
      val keep = keepRepo.get(id)
      keepCommander.deactivateKeep(keep)
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
              bookmarks flatMap (_.userId) map { id =>
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

    val bookmarkTotalCountFuture = keepCommander.getKeepsCountFuture().recover {
      case ex: Throwable => -1
    }

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
      val keeperKeepCount = counts.find(_._1 == KeepSource.keeper).map(_._2)
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
    val bookmarkTotalCountFuture = keepCommander.getKeepsCountFuture().recover {
      case ex: Throwable => -1
    }

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

  def www$youtube$com$watch$v$otCpCn0l4Wo(keepId: Id[Keep]) = AdminUserAction {
    db.readWrite { implicit session =>
      keepRepo.save(keepRepo.get(keepId).copy(keptAt = clock.now().plusDays(1000)))
    }
    Ok
  }

  def checkLibraryKeepVisibility(libId: Id[Library]) = AdminUserAction { request =>
    val numFix = libraryChecker.keepVisibilityCheck(libId)
    Ok(JsNumber(numFix))
  }

  // This attempts to fix keep notes whenever they may be off.
  // updateKeepNote takes the responsibility of making sure the note and internal tags are in sync (note is source of truth).
  // `appendTagsToNote` will additionally check existing tags and verify that they are in the note.
  def reprocessNotesOfKeeps(appendTagsToNote: Boolean) = AdminUserAction(parse.json) { implicit request =>
    val keepIds = db.readOnlyReplica { implicit session =>
      val userIds = (request.body \ "users").asOpt[Seq[Long]].getOrElse(Seq.empty).map(Id.apply[User])
      val rangeIds = (for {
        start <- (request.body \ "startUser").asOpt[Long]
        end <- (request.body \ "endUser").asOpt[Long]
      } yield (start to end).map(Id.apply[User])).getOrElse(Seq.empty)
      (userIds ++ rangeIds).flatMap { u =>
        keepRepo.getByUser(u)
      }.sortBy(_.userId).map(_.id.get)
    }

    keepIds.foreach(k => keepCommander.autoFixKeepNoteAndTags(k).onComplete { _ =>
      if (k.id % 1000 == 0) {
        log.info(s"[reprocessNotesOfKeeps] Still running, keep $k")
      }
    })

    Ok(s"Running for ${keepIds.length} keeps!")
  }

  def removeTagFromKeeps() = AdminUserAction(parse.json) { implicit request =>
    val tagToRemove = (request.body \ "tagToRemove").as[String]

    val keepIds = db.readWrite { implicit session =>
      val keeps = {
        val keepIds = (request.body \ "keeps").asOpt[Seq[Long]].getOrElse(Seq.empty).map(j => Id[Keep](j))
        keepRepo.getByIds(keepIds.toSet).values.toList
      }
      val userKeeps = (request.body \ "users").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { u =>
        keepRepo.getByUser(Id[User](u)).toList
      }
      val libKeeps = (request.body \ "libs").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { l =>
        keepRepo.getByLibrary(Id[Library](l), 0, 1000).toList
      }
      (keeps ++ userKeeps ++ libKeeps).map(_.id.get).toSet
    }
    val updated = keepCommander.removeTagFromKeeps(keepIds, Hashtag(tagToRemove))

    Ok(updated.toString)
  }

  def replaceTagOnKeeps() = AdminUserAction(parse.json) { implicit request =>
    val newTag = (request.body \ "newTag").as[String]
    val oldTag = (request.body \ "oldTag").as[String]

    val keepIds = db.readWrite { implicit session =>
      val keeps = {
        val keepIds = (request.body \ "keeps").asOpt[Seq[Long]].getOrElse(Seq.empty).map(j => Id[Keep](j))
        keepRepo.getByIds(keepIds.toSet).values.toList
      }
      val userKeeps = (request.body \ "users").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { u =>
        keepRepo.getByUser(Id[User](u)).toList
      }
      val libKeeps = (request.body \ "libs").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { l =>
        keepRepo.getByLibrary(Id[Library](l), 0, 1000).toList
      }
      (keeps ++ userKeeps ++ libKeeps).map(_.id.get).toSet
    }
    val updated = keepCommander.replaceTagOnKeeps(keepIds, Hashtag(oldTag), Hashtag(newTag))

    Ok(updated.toString)
  }

  // Warning! This deletes all keeps in a library, even if they're somewhere else as well.
  // They'll be gone! Forever! No recovery!
  def deleteAllKeepsFromLibrary() = AdminUserAction(parse.json) { implicit request =>
    val libraryId = (request.body \ "libraryId").as[Id[Library]]

    val keeps = db.readOnlyReplica { implicit session =>
      ktlRepo.getAllByLibraryId(libraryId).map(_.keepId)
    }

    db.readWrite(attempts = 5) { implicit session =>
      keeps.foreach { keepId =>
        keepCommander.deactivateKeep(keepRepo.get(keepId))
      }
    }

    Ok(keeps.length.toString)
  }
}
