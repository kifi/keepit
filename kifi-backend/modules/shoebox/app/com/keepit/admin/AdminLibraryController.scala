package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryInfoCommander, LibraryCommander, LibrarySuggestedSearchCommander }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber, State }
import com.keepit.common.net.RichRequestHeader
import com.keepit.common.queue.messages.SuggestedSearchTerms
import com.keepit.common.time._
import com.keepit.common.util.Paginator
import com.keepit.cortex.CortexServiceClient
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.search.SearchServiceClient
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Action
import views.html
import com.keepit.slack.models._

import scala.concurrent.Future
import scala.util.Try

case class LibraryStatistic(
  library: Library,
  owner: User,
  numKeeps: Int,
  numMembers: Int,
  numInvites: Int)

case class LibraryPageInfo(
  libraryStats: Seq[LibraryStatistic],
  hotTodayWithStats: Seq[(Double, LibraryStatistic)],
  topDailyFollower: Seq[(Int, LibraryStatistic)],
  topDailyKeeps: Seq[(Int, LibraryStatistic)],
  libraryCount: Int,
  page: Int,
  pageSize: Int)

class AdminLibraryController @Inject() (
    val userActionsHelper: UserActionsHelper,
    keepRepo: KeepRepo,
    normalizedURIRepo: NormalizedURIRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    libraryRepo: LibraryRepo,
    ktlRepo: KeepToLibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryAliasRepo: LibraryAliasRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryCommander: LibraryCommander,
    libraryImageRepoImpl: LibraryImageRepoImpl,
    userRepo: UserRepo,
    cortex: CortexServiceClient,
    db: Database,
    clock: Clock,
    searchClient: SearchServiceClient,
    suggestedSearchCommander: LibrarySuggestedSearchCommander,
    slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  def updateLibraryOwner(libraryId: Id[Library], fromUserId: Id[User], toUserId: Id[User]) = AdminUserPage { implicit request =>
    db.readWrite { implicit session =>
      val lib = libraryRepo.get(libraryId)
      if (lib.ownerId != fromUserId) throw new Exception(s"orig user $fromUserId is not matching current library owner $lib")
      libraryAliasRepo.alias(lib.ownerId, lib.slug, lib.id.get)
      libraryAliasRepo.reclaim(toUserId, lib.slug) // reclaim existing alias to a former library of toUserId with the same slug
      val newOwnerLib = lib.copy(ownerId = toUserId)
      libraryRepo.save(newOwnerLib)
      val currentOwnership = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, fromUserId).getOrElse(throw new Exception(s"no ownership to lib $lib for user $fromUserId"))
      libraryMembershipRepo.save(currentOwnership.copy(access = LibraryAccess.READ_ONLY))
      libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, toUserId) match {
        case None =>
          libraryMembershipRepo.save(currentOwnership.copy(id = None, userId = toUserId))
        case Some(newOwnership) =>
          libraryMembershipRepo.save(newOwnership.copy(access = LibraryAccess.OWNER, showInSearch = currentOwnership.showInSearch))
      }
      var page = 0
      val pageSize = 100
      var hasMore = true
      val keeps: Int = 0
      while (hasMore) {
        val from = page * pageSize
        val chunk: Seq[Keep] = keepRepo.pageByLibrary(libraryId, from, from + pageSize, Set.empty) map { keep =>
          val tags = keepToCollectionRepo.getByKeep(keep.id.get)
          tags foreach { keepToTag =>
            val origTag = collectionRepo.get(keepToTag.collectionId)
            val tag = collectionRepo.getByUserAndName(toUserId, origTag.name, excludeState = None) match {
              case None => collectionRepo.save(Collection(userId = toUserId, name = origTag.name))
              case Some(existing) if !existing.isActive => collectionRepo.save(existing.copy(state = CollectionStates.ACTIVE, name = origTag.name))
              case Some(existing) => existing
            }
            keepToCollectionRepo.save(keepToTag.copy(collectionId = tag.id.get))
          }
          keepRepo.save(keep.withOwner(toUserId))
        }
        hasMore = chunk.size >= pageSize
        keeps + chunk.size
        page += 1
      }
      Ok(s"keep count = $keeps for library: $newOwnerLib")
    }
  }

  private def buildLibStatistic(library: Library, owner: User)(implicit session: RSession): LibraryStatistic = {
    require(library.ownerId == owner.id.get, "Library and Owner do not match.")
    val keepsCount = ktlRepo.getCountByLibraryId(library.id.get)
    val membershipsCount = libraryMembershipRepo.countWithLibraryId(library.id.get)
    val invitesCount = libraryInviteRepo.getWithLibraryId(library.id.get).length
    LibraryStatistic(library, owner, keepsCount, membershipsCount, invitesCount)
  }

  def index(page: Int = 0) = AdminUserPage { implicit request =>
    val pageSize = 30
    val topListSize = 30
    val (stats, hotTodayWithStats, topDailyFollower, topDailyKeeps, totalPublishedCount) = db.readOnlyReplica { implicit session =>
      val hotToday = if (page == 0) {
        libraryMembershipRepo.percentGainSince(clock.now().minusHours(24), totalMoreThan = 10, recentMoreThan = 10, count = topListSize)
      } else Seq()

      val topDailyFollower = if (page == 0) {
        libraryMembershipRepo.mostMembersSince(topListSize, clock.now().minusHours(24))
      } else Seq()

      val topDailyKeeps = if (page == 0) {
        ktlRepo.publishedLibrariesWithMostKeepsSince(Limit(topListSize), clock.now().minusHours(24)).toSeq
      } else Seq()

      val pagePublished = libraryRepo.pagePublished(Paginator(page, pageSize))

      val statsByLibraryId = {
        val pagedLibrariesById = pagePublished.map(lib => lib.id.get -> lib).toMap
        val allLibraryIds = pagedLibrariesById.keySet ++ topDailyFollower.map(_._1) ++ topDailyKeeps.map(_._1) ++ hotToday.map(_._1)
        val librariesById = {
          val missingLibraryIds = allLibraryIds -- pagedLibrariesById.keys
          val missingLibraries = if (missingLibraryIds.nonEmpty) libraryRepo.getActiveByIds(missingLibraryIds) else Map.empty
          pagedLibrariesById ++ missingLibraries
        }
        val usersById = userRepo.getAllUsers(librariesById.values.map(_.ownerId).toSeq)
        librariesById.map { case (libId, lib) => libId -> buildLibStatistic(lib, usersById(lib.ownerId)) }
      }

      val hotTodayWithStats = hotToday.map { case (libId, _, _, growth) => (growth, statsByLibraryId(libId)) }
      val topDailyFollowerWithStats = topDailyFollower.map { case (libId, count) => (count, statsByLibraryId(libId)) }
      val topDailyKeepsWithStats = topDailyKeeps.map { case (libId, count) => (count, statsByLibraryId(libId)) }
      val pagePublishedWithStats = pagePublished.map { lib => statsByLibraryId(lib.id.get) }

      (pagePublishedWithStats, hotTodayWithStats, topDailyFollowerWithStats, topDailyKeepsWithStats, libraryRepo.countPublished)
    }
    val info = LibraryPageInfo(libraryStats = stats, hotTodayWithStats = hotTodayWithStats, topDailyFollower = topDailyFollower, topDailyKeeps = topDailyKeeps, libraryCount = totalPublishedCount, page = page, pageSize = pageSize)
    Ok(html.admin.libraries(info))
  }

  def libraryView(libraryId: Id[Library], transfer: Boolean = false) = AdminUserPage.async { implicit request =>
    val simLibsFut = cortex.similarLibraries(libraryId, 5).map { libIds =>
      db.readOnlyReplica { implicit s =>
        libIds.map(libraryRepo.get)
      }
    }
    val (library, owner, keepCount, contributors, followers, suggestedSearches) = db.readOnlyReplica { implicit session =>
      val lib = libraryRepo.get(libraryId)
      val owner = userRepo.get(lib.ownerId)
      val keepCount = ktlRepo.getCountByLibraryId(libraryId)
      val members = libraryMembershipRepo.getWithLibraryId(libraryId)

      val contributors = members.filter(x => x.access == LibraryAccess.READ_WRITE).map { m => userRepo.get(m.userId) }
      val followers = members.filter(x => x.access == LibraryAccess.READ_ONLY).map { m => userRepo.get(m.userId) }
      val terms = suggestedSearchCommander.getSuggestedTermsForLibrary(libraryId, limit = 25, SuggestedSearchTermKind.AUTO)
      (lib, owner, keepCount, contributors, followers, terms)
    }

    simLibsFut.map { relatedLibs =>
      val termsStr = suggestedSearches.terms.map { case (t, w) => t + ":" + w.toString }.mkString(", ")
      Ok(html.admin.library(library, owner, keepCount, contributors, followers, Library.publicId(libraryId), relatedLibs, termsStr, transfer))
    }
  }

  def libraryKeepsView(libraryId: Id[Library], page: Int = 0, showPrivates: Boolean = false, showInactives: Boolean = false) = AdminUserPage { implicit request =>
    if (showPrivates) {
      log.warn(s"${request.user.firstName} ${request.user.firstName} (${request.userId}) is viewing private library $libraryId")
    }

    val excludeKeepStateSet = if (showInactives) Set.empty[State[Keep]] else Set(KeepStates.INACTIVE)

    val pageSize = 50
    val (library, owner, totalKeepCount, keepInfos) = db.readOnlyReplica { implicit session =>
      val lib = libraryRepo.get(libraryId)
      val owner = userRepo.get(lib.ownerId)
      val keepCount = ktlRepo.getCountByLibraryId(libraryId)
      val keeps = keepRepo.pageByLibrary(libraryId, page * pageSize, pageSize, excludeKeepStateSet).filter(b => showPrivates || !lib.isSecret)

      val keepInfos = for (keep <- keeps) yield {
        val tagNames = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).map(collectionRepo.get).map(_.name)
        (keep, normalizedURIRepo.get(keep.uriId), keep.userId.map(userRepo.get), tagNames)
      }
      (lib, owner, keepCount, keepInfos)
    }
    Ok(html.admin.libraryKeeps(library, owner, totalKeepCount, keepInfos, page, pageSize))
  }

  def internUserSystemLibraries(userId: Id[User]) = AdminUserPage { implicit request =>
    val res = libraryCommander.internSystemGeneratedLibraries(userId)

    Ok(res.toString)
  }

  def internAllUserSystemLibraries(startingUserId: Id[User], endingUserId: Id[User]) = AdminUserPage { implicit request =>
    val ids = (startingUserId.id to endingUserId.id).map(Id[User])

    val confirmedIds = db.readOnlyReplica { implicit session =>
      ids.map { idCandidate =>
        scala.util.Try(userRepo.getNoCache(idCandidate)).toOption.map(_.id.get)
      }
    }.flatten

    val result = confirmedIds.map { userId =>
      userId.id + " -> " + libraryCommander.internSystemGeneratedLibraries(userId)
    }

    Ok(s"count: ${result.size}<br>\n<br>\n" + result.mkString("<br>\n"))
  }

  def changeState(libraryId: Id[Library], state: String) = AdminUserAction { request =>
    val libState = state match {
      case LibraryStates.ACTIVE.value => LibraryStates.ACTIVE
      case LibraryStates.INACTIVE.value => LibraryStates.INACTIVE
    }
    db.readWrite(implicit s => libraryRepo.save(libraryRepo.get(libraryId).withState(libState)))
    Ok
  }

  //post request with a list of private/public and active/inactive
  def updateLibraries() = AdminUserPage { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setToVisibility(id: Id[Library], newVisibility: String)(implicit session: RWSession): Id[User] = {
      val lib = libraryRepo.get(id)
      log.info("updating library %s to %s".format(lib, newVisibility))
      newVisibility match {
        case LibraryVisibility.PUBLISHED.value => libraryRepo.save(lib.copy(visibility = LibraryVisibility.PUBLISHED))
        case LibraryVisibility.DISCOVERABLE.value => libraryRepo.save(lib.copy(visibility = LibraryVisibility.DISCOVERABLE))
        case LibraryVisibility.SECRET.value => libraryRepo.save(lib.copy(visibility = LibraryVisibility.SECRET))
      }

      log.info("updated library %s to %s".format(lib, newVisibility))
      lib.ownerId
    }

    def setIsActive(id: Id[Library], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val lib = libraryRepo.get(id)
      log.info("updating bookmark %s with active = %s".format(lib, isActive))
      if (isActive)
        libraryRepo.save(lib.copy(state = LibraryStates.ACTIVE))
      else
        libraryRepo.save(lib.copy(state = LibraryStates.INACTIVE))
      log.info("updated bookmark %s".format(lib))
      lib.ownerId
    }

    db.readWrite { implicit s =>
      request.body.asFormUrlEncoded.get foreach {
        case (key, values) =>
          key.split("_") match {
            case Array("active", id) => setIsActive(Id[Library](id.toInt), toBoolean(values.last))
            case Array("visib", id) => setToVisibility(Id[Library](id.toInt), values.last)
          }
      }
    }
    log.info("updating changed users")
    Redirect(request.request.referer)
  }

  def getLuceneDocument(libraryId: Id[Library]) = AdminUserPage.async { implicit request =>
    val library = db.readOnlyMaster { implicit session =>
      libraryRepo.get(libraryId)
    }
    searchClient.getLibraryDocument(Library.toDetailedLibraryView(library)).map(Ok(_))
  }

  def saveSuggestedSearches() = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val libId = body.get("libId").get.toLong
    val tc = body.get("tc").get
    val terms = tc.trim.split(", ").map { token => val Array(term, weight) = token.split(":"); (term.trim, weight.trim.toFloat) }.toMap
    suggestedSearchCommander.saveSuggestedSearchTermsForLibrary(Id[Library](libId), SuggestedSearchTerms(terms), SuggestedSearchTermKind.AUTO)
    Ok
  }

  def unsafeAddMember = AdminUserAction(parse.tolerantJson) { implicit request =>
    val userId = (request.body \ "userId").as[Id[User]]
    val libraryId = (request.body \ "libraryId").as[Id[Library]]
    val access = (request.body \ "access").asOpt[LibraryAccess].getOrElse(LibraryAccess.READ_ONLY)
    db.readWrite { implicit session =>
      val existingMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId, None)
      val newMembershipTemplate = LibraryMembership(
        libraryId = libraryId,
        userId = userId,
        access = access
      )
      val newMembership = libraryMembershipRepo.save(newMembershipTemplate.copy(id = existingMembershipOpt.flatMap(_.id)))
      Ok(Json.toJson(newMembership))
    }
  }

  def setLibraryOwner(libId: Id[Library]) = AdminUserPage { implicit request =>
    val body = request.body.asFormUrlEncoded.get.mapValues(_.head)
    val newOwner = Id[User](body.get("user-id").get.toLong)
    val orgIdOpt = body.get("org-id").flatMap(id => Try(id.toLong).toOption).map(id => Id[Organization](id))
    db.readWrite { implicit session =>
      libraryCommander.unsafeTransferLibrary(libId, newOwner)
    }
    val modifyRequest = orgIdOpt match {
      case Some(orgId) => LibraryModifications(space = Some(LibrarySpace.fromOrganizationId(orgId)), visibility = Some(LibraryVisibility.ORGANIZATION))
      case None => LibraryModifications(space = Some(LibrarySpace.fromUserId(newOwner)), visibility = Some(LibraryVisibility.PUBLISHED))
    }
    implicit val context = HeimdalContext.empty // TODO(ryan): ask someone that cares to make a HeimdalContext.admin(request) method
    libraryCommander.modifyLibrary(libId, newOwner, modifyRequest)
    Redirect(com.keepit.controllers.admin.routes.AdminLibraryController.libraryView(libId))
  }

  def unsafeMoveLibraryKeeps = AdminUserAction.async(parse.tolerantJson) { implicit request =>
    val fromLibraryId = (request.body \ "fromLibrary").as[Id[Library]]
    val toLibraryId = (request.body \ "toLibrary").as[Id[Library]]
    val userId = db.readOnlyReplica { implicit session =>
      val (fromLib, toLib) = (libraryRepo.get(fromLibraryId), libraryRepo.get(toLibraryId))
      require(fromLib.ownerId == toLib.ownerId)
      fromLib.ownerId
    }
    implicit val context = HeimdalContext.empty
    Future {
      val (successes, fails) = libraryCommander.moveAllKeepsFromLibrary(userId, fromLibraryId, toLibraryId)
      Ok(Json.obj("moved" -> successes, "failures" -> fails.map(_._1)))
    }
  }

  def unsafeModifyLibrary = AdminUserAction(parse.tolerantJson) { implicit request =>
    val libId = (request.body \ "libraryId").as[Id[Library]]
    val mods = (request.body \ "modifications").as[LibraryModifications](LibraryModifications.adminReads)
    val lib = db.readOnlyMaster { implicit session => libraryRepo.get(libId) }
    val response = libraryCommander.unsafeModifyLibrary(lib, mods)
    Ok(Json.obj("lib" -> response.modifiedLibrary))
  }

  def backfillSlackLibraryNames = AdminUserPage { implicit request =>
    def formatSlackChannelName(name: String): String = {
      name.replaceAll("[_\\-#@]", " ").split(" ").map(_.trim).filter(_.nonEmpty).map(s => s.capitalize).mkString(" ")
    }

    val actuallySaveLibs = request.rawQueryString.contains("doItForReal")

    val total = (0 to 100).flatMap { idx => // ~18k existing rows
      db.readWrite { implicit session =>
        val libIds = slackChannelToLibraryRepo.pageAscending(0, 200, Set(SlackChannelToLibraryStates.INACTIVE)).map(_.libraryId)
        log.info(s"[backfillSlackLibraryNames] Page $idx, ${libIds.length} libs grabbed.")
        libraryRepo.getActiveByIds(libIds.toSet).values.filter(_.kind == LibraryKind.SLACK_CHANNEL).map { library =>
          val newName = formatSlackChannelName(library.name)
          log.info(s"[backfillSlackLibraryNames] Changing ${library.id.get} from ${library.name} to $newName")
          if (actuallySaveLibs && library.name != newName) {
            libraryRepo.save(library.withName(newName))
          }
          1
        }
      }
    }.sum

    Ok(total.toString)
  }
}
