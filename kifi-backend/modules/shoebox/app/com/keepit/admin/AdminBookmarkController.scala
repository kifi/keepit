package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ChunkedResponseHelper
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.logging.SlackLog
import com.keepit.common.performance._
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.integrity.LibraryChecker
import com.keepit.model.{ KeepStates, _ }
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.slack.{ InhouseSlackClient, InhouseSlackChannel }
import com.keepit.social.{ IdentityHelpers, UserIdentityHelper, Author }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent }
import securesocial.core.IdentityId
import views.html
import com.keepit.common.core._

import scala.collection.mutable
import scala.collection.mutable.{ HashMap => MutableMap }
import scala.concurrent._
import scala.util.Try

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
  keepSourceCommander: KeepSourceCommander,
  userIdentityHelper: UserIdentityHelper,
  uriInterner: NormalizedURIInterner,
  eliza: ElizaServiceClient,
  implicit val inhouseSlackClient: InhouseSlackClient,
  implicit val imageConfig: S3ImageConfig)
    extends AdminUserActions {

  val slackLog = new SlackLog(InhouseSlackChannel.TEST_CAM)

  private def editBookmark(bookmark: Keep)(implicit request: UserRequest[AnyContent]) = {
    db.readOnlyMaster { implicit session =>
      val uri = uriRepo.get(bookmark.uriId)
      val user = bookmark.userId.map(userRepo.get)
      val keepId = bookmark.id.get
      val keywordsFut = keywordSummaryCommander.getKeywordsSummary(bookmark.uriId)
      val imageUrlOpt = keepImageCommander.getBasicImagesForKeeps(Set(keepId)).get(keepId).flatMap(_.get(ProcessedImageSize.Large.idealSize).map(_.path.getUrl))
      val libraryOpt = bookmark.lowestLibraryId.map { opt => libraryRepo.get(opt) }

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
      Future { db.readOnlyReplica { implicit s => keepRepo.page(page, PAGE_SIZE, Set(KeepStates.INACTIVE)) } } flatMap { bookmarks =>
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

    for {
      bookmarksAndUsers <- bookmarksInfos()
      overallCount <- bookmarkTotalCountFuture
      counts <- bookmarkTodayAllCountsFuture
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
      Ok(html.admin.bookmarks(bookmarksAndUsers, page, overallCount, pageCount, keeperKeepCount, tweakedCounts, total))
    }
  }

  def userBookmarkKeywords = AdminUserPage.async { implicit request =>
    val user = request.userId
    val uris = db.readOnlyReplica { implicit s =>
      keepRepo.getLatestKeepsURIByUser(user, 500)
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
        keepRepo.getByIds(keepIds.toSet).keySet
      }
      val userKeeps = (request.body \ "users").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { u =>
        keepRepo.getByUser(Id[User](u)).map(_.id.get).toSet
      }
      val libKeeps = (request.body \ "libs").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { l =>
        ktlRepo.pageByLibraryId(Id[Library](l), Offset(0), Limit(1000)).map(_.keepId).toSet
      }
      keeps ++ userKeeps ++ libKeeps
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
        keepRepo.getByIds(keepIds.toSet).keySet
      }
      val userKeeps = (request.body \ "users").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { u =>
        keepRepo.getByUser(Id[User](u)).map(_.id.get).toSet
      }
      val libKeeps = (request.body \ "libs").asOpt[Seq[Long]].getOrElse(Seq.empty).flatMap { l =>
        ktlRepo.getAllByLibraryId(Id[Library](l)).map(_.keepId).toSet
      }
      keeps ++ userKeeps ++ libKeeps
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

  def reattributeKeeps(author: String, userIdOpt: Option[Long], overwriteExistingOwner: Boolean, doIt: Boolean) = AdminUserAction { implicit request =>
    Try(Author.fromIndexableString(author)).toOption match {
      case Some(validAuthor) =>
        val authorOwnerId: Option[Id[User]] = db.readOnlyMaster { implicit session =>
          validAuthor match {
            case Author.KifiUser(kifiUserId) => Some(kifiUserId)
            case Author.SlackUser(slackTeamId, slackUserId) => userIdentityHelper.getOwnerId(IdentityHelpers.toIdentityId(slackTeamId, slackUserId))
            case Author.TwitterUser(twitterUserId) => userIdentityHelper.getOwnerId(IdentityId(twitterUserId.id.toString, "twitter"))
          }
        }
        (authorOwnerId orElse userIdOpt.map(Id[User])) match {
          case None => BadRequest(s"No user found.")
          case Some(userId) =>
            if (doIt) SafeFuture { keepSourceCommander.reattributeKeeps(validAuthor, userId, overwriteExistingOwner) }
            Ok(s"Reattribute keeps from $author to user $userId. Overwriting existing keep owner? $overwriteExistingOwner. Doing it? $doIt. ")
        }
      case None => BadRequest("invalid_author")
    }

  }

  def backfillKifiSourceAttribution(startFrom: Option[Long], limit: Int, dryRun: Boolean = true) = AdminUserAction { implicit request =>
    val fromId = startFrom.map(Id[Keep])
    val chunkSize = 100
    val keepsToBackfill = db.readOnlyMaster(implicit s => keepRepo.pageAscendingWithUserExcludingSources(fromId, limit, excludeStates = Set.empty, excludeSources = Set(KeepSource.slack, KeepSource.twitterFileImport, KeepSource.twitterSync))).toSet
    val enum = ChunkedResponseHelper.chunkedFuture(keepsToBackfill.grouped(chunkSize).toSeq) { keeps =>
      val (discussionKeeps, otherKeeps) = keeps.partition(_.source == KeepSource.discussion)
      val discussionConnectionsFut = eliza.getInitialRecipientsByKeepId(discussionKeeps.map(_.id.get)).map { connectionsByKeep =>
        discussionKeeps.flatMap { keep =>
          connectionsByKeep.get(keep.id.get).map { connections =>
            keep.id.get -> (RawKifiAttribution(keep.userId.get, connections, keep.source), keep.state == KeepStates.ACTIVE)
          }
        }.toMap
      }

      val nonDiscussionConnectionsFut = db.readOnlyMasterAsync { implicit s =>
        val ktls = ktlRepo.getAllByKeepIds(otherKeeps.map(_.id.get), excludeStateOpt = None)
        val ktus = ktuRepo.getAllByKeepIds(otherKeeps.map(_.id.get), excludeState = None)
        otherKeeps.collect {
          case keep =>
            val firstLibrary = ktls(keep.id.get).minBy(_.addedAt).libraryId
            val firstUsers = ktus(keep.id.get).filter(ktu => keep.keptAt.getMillis > ktu.addedAt.minusSeconds(1).getMillis)
            val rawAttribution = RawKifiAttribution(keptBy = keep.userId.get, KeepConnections(Set(firstLibrary), Set.empty, firstUsers.map(_.userId).toSet), keep.source)
            keep.id.get -> (rawAttribution, keep.state == KeepStates.ACTIVE)
        }.toMap
      }

      for {
        discussionConnections <- discussionConnectionsFut
        nonDiscussionConnections <- nonDiscussionConnectionsFut
        (success, fail) <- db.readWriteAsync { implicit s =>
          val allConnections = discussionConnections ++ nonDiscussionConnections
          val missingKeeps = keepsToBackfill.map(_.id.get).filter(!allConnections.contains(_))
          val internedKeeps = allConnections.map {
            case (kid, (attr, isActive)) =>
              val state = if (isActive) KeepSourceAttributionStates.ACTIVE else KeepSourceAttributionStates.INACTIVE
              if (!dryRun) sourceRepo.intern(kid, attr, state = state) else slackLog.info(s"$kid: ${Json.stringify(Json.toJson(attr))}")
              kid
          }
          (internedKeeps, missingKeeps)
        }
      } yield {
        s"${keeps.minByOpt(_.id.get)}-${keeps.maxByOpt(_.id.get)}: interned ${success.size}, failed on ${fail.mkString("(", ",", ")")}\n"
      }
    }
    Ok.chunked(enum)
  }
}
