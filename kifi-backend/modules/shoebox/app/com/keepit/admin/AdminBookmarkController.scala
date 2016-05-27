package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.{ ChunkedResponseHelper, FutureHelpers }
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper, UserRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.common.logging.SlackLog
import com.keepit.common.performance._
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.integrity.LibraryChecker
import com.keepit.model.{ KeepStates, _ }
import com.keepit.normalizer.NormalizedURIInterner
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import com.keepit.social.{ Author, IdentityHelpers, UserIdentityHelper }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.{ Action, AnyContent }
import securesocial.core.IdentityId
import views.html
import com.keepit.common.core._
import com.keepit.social.twitter.RawTweet

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
  keepMutator: KeepMutator,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  libraryChecker: LibraryChecker,
  clock: Clock,
  sourceRepo: KeepSourceAttributionRepo,
  keepSourceCommander: KeepSourceCommander,
  keepEventRepo: KeepEventRepo,
  rawKeepRepo: RawKeepRepo,
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
      val imageUrlOpt = keepImageCommander.getBasicImagesForKeeps(Set(keepId)).get(keepId).flatMap(_.get(ProcessedImageSize.Large.idealSize).map(_.path.getImageUrl))
      val libraryOpt = bookmark.lowestLibraryId.map { opt => libraryRepo.get(opt) }

      keywordsFut.map { keywords =>
        Ok(html.admin.bookmark(user, bookmark, uri, imageUrlOpt.fold("")(_.value), "", keywords, libraryOpt))
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
      keepMutator.deactivateKeep(keep)
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

    val bookmarkTotalCountFuture = keepCommander.getKeepsCountFuture.recover {
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
      val keeperKeepCount = counts.find(_._1 == KeepSource.Keeper).map(_._2)
      val total = counts.map(_._2).sum
      val tweakedCounts = counts.map {
        case cnt if cnt._1 == KeepSource.BookmarkFileImport => ("Unknown/other file import", cnt._2)
        case cnt if cnt._1 == KeepSource.BookmarkImport => ("Browser bookmark import", cnt._2)
        case cnt if cnt._1 == KeepSource.Default => ("Default new user keeps", cnt._2)
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
        (embedlyKeys zip word2vecKeys).foreach {
          case (emb, w2v) =>
            val s1 = emb.map { _.name }.toSet
            val s2 = w2v.map { _.cosine }.getOrElse(Seq()).toSet
            val s3 = w2v.map { _.freq }.getOrElse(Seq()).toSet
            (s1.union(s2.intersect(s3))).foreach { word => keyCounts(word) = keyCounts(word) + 1 }
        }
        Ok(html.admin.UserKeywords(user, keyCounts.toArray.sortBy(-1 * _._2).take(100)))
    }
  }

  def checkLibraryKeepVisibility(libId: Id[Library]) = AdminUserAction { request =>
    val numFix = libraryChecker.keepVisibilityCheck(libId)
    Ok(JsNumber(numFix))
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
        keepMutator.deactivateKeep(keepRepo.get(keepId))
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

  def backfillTwitterAttribution(userIdsString: String) = AdminUserAction { implicit request =>
    val userIds = userIdsString.split(",").map(idStr => Id[User](idStr.trim.toLong)).toSeq
    SafeFuture {
      def isFromTwitter(source: KeepSource) = source == KeepSource.TwitterSync || source == KeepSource.TwitterFileImport
      userIds.foreach { userId =>
        db.readWrite { implicit session =>
          log.info(s"[backfillTwitterAttribution] Processing user $userId.")
          val rawKeeps = rawKeepRepo.getByUserId(userId)
          log.info(s"[backfillTwitterAttribution] Found ${rawKeeps.length} for user $userId.")
          var fixed = 0
          val rawKeepsFromTwitter = rawKeeps.filter(r => isFromTwitter(r.source))
          rawKeepsFromTwitter.foreach { rawKeep =>
            rawKeep.originalJson.foreach { sourceJson =>
              RawTweet.format.reads(sourceJson).foreach { rawTweet =>
                uriInterner.getByUri(rawKeep.url).foreach { uri =>
                  val keepsForRawKeep = keepRepo.getByUri(uri.id.get, excludeState = None).filter { keep =>
                    keep.source == rawKeep.source &&
                      keep.userId.contains(rawKeep.userId) &&
                      rawKeep.libraryId.toSet == keep.recipients.libraries &&
                      rawKeep.createdDate.contains(keep.keptAt)
                  }
                  val sourceByKeepId = sourceRepo.getRawByKeepIds(keepsForRawKeep.flatMap(_.id).toSet)
                  def isMissingAttribution(keep: Keep) = !sourceByKeepId.contains(keep.id.get)
                  keepsForRawKeep match {
                    // Unambiguous fix
                    case Seq(keep) if isMissingAttribution(keep) =>
                      fixed += 1
                      sourceRepo.intern(keep.id.get, RawTwitterAttribution(rawTweet))
                    case multipleKeeps =>
                      if (multipleKeeps.exists(isMissingAttribution)) {
                        log.info(s"[backfillTwitterAttribution] RawKeep ${rawKeep.id} matches several keeps including one problematic: $keepsForRawKeep.")
                      }
                  }
                }
              }
            }
          }
          log.info(s"[backfillTwitterAttribution] Fixed $fixed keeps for user $userId.")
        }
      }
    }
    Ok(s"Ok. It's gonna take a while.")
  }

  def backfillKifiSourceAttribution(startFrom: Option[Long], limit: Int, dryRun: Boolean) = AdminUserAction { implicit request =>
    import com.keepit.common.core._

    var fromId = startFrom.map(Id[Keep])
    val chunkSize = 100
    val numPages = limit / chunkSize
    val enum = ChunkedResponseHelper.chunkedFuture(1 to numPages) { page =>
      val keeps = db.readOnlyMaster(implicit s => keepRepo.pageAscendingWithUserExcludingSources(fromId, chunkSize, excludeStates = Set.empty, excludeSources = Set(KeepSource.Slack, KeepSource.TwitterFileImport, KeepSource.TwitterSync)))
      def mightBeDiscussion(k: Keep) = k.source == KeepSource.Discussion || (k.isActive && k.recipients.libraries.isEmpty && k.recipients.users.exists(uid => !k.userId.contains(uid)))
      val (discussionKeeps, otherKeeps) = keeps.partition(mightBeDiscussion)
      val discussionConnectionsFut = eliza.getInitialRecipientsByKeepId(discussionKeeps.map(_.id.get).toSet).map { connectionsByKeep =>
        discussionKeeps.flatMap { keep =>
          connectionsByKeep.get(keep.id.get).map { connections =>
            keep.id.get -> (RawKifiAttribution(keep.userId.get, keep.note, connections, keep.source), keep.state == KeepStates.ACTIVE)
          }
        }.toMap
      }

      val nonDiscussionConnectionsFut = db.readOnlyMasterAsync { implicit s =>
        val ktls = ktlRepo.getAllByKeepIds(otherKeeps.map(_.id.get).toSet, excludeStateOpt = None)
        val ktus = ktuRepo.getAllByKeepIds(otherKeeps.map(_.id.get).toSet, excludeState = None)
        otherKeeps.collect {
          case keep =>
            val firstLibrary = ktls.getOrElse(keep.id.get, Seq.empty).minByOpt(_.addedAt).map(_.libraryId)
            val firstUsers = ktus.getOrElse(keep.id.get, Seq.empty).collect { case ktu if keep.keptAt.getMillis > ktu.addedAt.minusSeconds(1).getMillis => ktu.userId } ++ keep.userId.toSeq
            val rawAttribution = RawKifiAttribution(keptBy = keep.userId.get, keep.note, KeepRecipients(firstLibrary.toSet, Set.empty, firstUsers.toSet), keep.source)
            keep.id.get -> (rawAttribution, keep.state == KeepStates.ACTIVE)
        }.toMap
      }

      for {
        discussionConnections <- discussionConnectionsFut
        nonDiscussionConnections <- nonDiscussionConnectionsFut
        (success, fail) <- db.readWriteAsync { implicit s =>
          val fetchedConnections = discussionConnections ++ nonDiscussionConnections
          val missingKeeps = keeps.filter(keep => !fetchedConnections.contains(keep.id.get))
          val allConnections = fetchedConnections ++ missingKeeps.map { k =>
            val rawAttribution = RawKifiAttribution(keptBy = k.userId.get, k.note, k.recipients.plusUser(k.userId.get), k.source)
            k.id.get -> (rawAttribution, k.state == KeepStates.ACTIVE)
          }
          val internedKeeps = allConnections.map {
            case (kid, (attr, isActive)) =>
              val state = if (isActive) KeepSourceAttributionStates.ACTIVE else KeepSourceAttributionStates.INACTIVE
              if (!dryRun) sourceRepo.intern(kid, attr, state = state) else slackLog.info(s"$kid: ${Json.stringify(Json.toJson(attr))}")
              kid
          }
          (internedKeeps, missingKeeps)
        }
      } yield {
        fromId = keeps.maxBy(_.id.get).id
        s"${keeps.map(_.id.get).minMaxOpt}: interned ${success.size}, failed on ${fail.mkString("(", ",", ")")}\n"
      }
    }
    Ok.chunked(enum)
  }

  def backfillKeepEventRepo(fromId: Id[Message], pageSize: Int, dryRun: Boolean) = AdminUserAction.async { implicit request =>
    var startWithMessage = fromId
    FutureHelpers.doUntil {
      eliza.pageSystemMessages(startWithMessage, pageSize).map { msgs =>
        if (msgs.isEmpty) true
        else {
          msgs.foreach { msg =>
            msg.auxData.foreach { eventData =>
              if (!dryRun) {
                val event = KeepEvent(
                  state = if (msg.isDeleted) KeepEventStates.INACTIVE else KeepEventStates.ACTIVE,
                  keepId = msg.keep,
                  eventData = eventData,
                  eventTime = msg.sentAt,
                  source = msg.source.flatMap(KeepEventSource.fromMessageSource),
                  messageId = Some(msg.id)
                )
                Try(db.readWrite(implicit s => keepEventRepo.save(event))).failed.map {
                  case t: Throwable => slackLog.warn(s"failed on keep ${msg.keep}, msg ${msg.id}, reason ${t.getMessage}")
                }
              }
            }
          }
          slackLog.info(s"messages ${msgs.map(_.id).minMaxOpt}")
          startWithMessage = msgs.last.id
          false
        }
      }
    }.map(_ => NoContent)
  }
}
