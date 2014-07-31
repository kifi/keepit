package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ AuthenticatedRequest, ActionAuthenticator, AdminController }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.net.RichRequestHeader
import com.keepit.model._
import play.api.mvc.AnyContent
import views.html

case class LibraryStatistic(
  library: Library,
  owner: User,
  numKeeps: Int,
  numMembers: Int,
  numInvites: Int)

case class LibraryPageInfo(
  libraryStats: Seq[LibraryStatistic],
  libraryCount: Int,
  page: Int,
  pageSize: Int)

class AdminLibraryController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    keepRepo: KeepRepo,
    normalizedURIRepo: NormalizedURIRepo,
    keepToCollectionRepo: KeepToCollectionRepo,
    collectionRepo: CollectionRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    libraryCommander: LibraryCommander,
    userRepo: UserRepo,
    db: Database) extends AdminController(actionAuthenticator) {

  def index(page: Int = 0) = AdminHtmlAction.authenticated { implicit request =>
    val pageSize = 30
    val (stats, totalCount) = db.readOnlyReplica { implicit session =>
      val stats = libraryRepo.page(page, size = pageSize).map { lib =>
        val owner = userRepo.get(lib.ownerId)
        val keepsCount = keepRepo.getCountByLibrary(lib.id.get)
        val memberships = libraryMembershipRepo.getWithLibraryId(lib.id.get)
        val invites = libraryInviteRepo.getWithLibraryId(lib.id.get)
        LibraryStatistic(lib, owner, keepsCount, memberships.length, invites.length)
      }
      (stats, libraryRepo.count)
    }
    val info = LibraryPageInfo(libraryStats = stats, libraryCount = totalCount, page = page, pageSize = pageSize)
    Ok(html.admin.libraries(info))
  }

  def libraryView(libraryId: Id[Library]) = AdminHtmlAction.authenticated { implicit request =>
    val (library, owner, keepCount, contributors, followers) = db.readOnlyReplica { implicit session =>
      val lib = libraryRepo.get(libraryId)
      val owner = userRepo.get(lib.ownerId)
      val keepCount = keepRepo.getCountByLibrary(libraryId)
      val members = libraryMembershipRepo.getWithLibraryId(libraryId)

      val contributors = members.filter(x => (x.access == LibraryAccess.READ_WRITE || x.access == LibraryAccess.READ_INSERT)).map { m => userRepo.get(m.userId) }
      val followers = members.filter(x => x.access == LibraryAccess.READ_ONLY).map { m => userRepo.get(m.userId) }

      (lib, owner, keepCount, contributors, followers)
    }
    Ok(html.admin.library(library, owner, keepCount, contributors, followers))
  }

  def libraryKeepsView(libraryId: Id[Library], showPrivates: Boolean = false) = AdminHtmlAction.authenticated { implicit request =>
    if (showPrivates) {
      log.warn(s"${request.user.firstName} ${request.user.firstName} (${request.userId}) is viewing private library $libraryId")
    }

    val (library, owner, totalKeepCount, keepInfos) = db.readOnlyReplica { implicit session =>
      val lib = libraryRepo.get(libraryId)
      val owner = userRepo.get(lib.ownerId)
      val keeps = keepRepo.getByLibrary(libraryId).filter(b => showPrivates || !(b.isPrivate || lib.visibility == LibraryVisibility.SECRET))

      val keepInfos = for (keep <- keeps) yield {
        val tagNames = keepToCollectionRepo.getCollectionsForKeep(keep.id.get).map(collectionRepo.get).map(_.name)
        (keep, normalizedURIRepo.get(keep.uriId), userRepo.get(keep.userId), tagNames)
      }
      (lib, owner, keeps.length, keepInfos)
    }
    Ok(html.admin.libraryKeeps(library, owner, totalKeepCount, keepInfos))
  }

  def internUserSystemLibraries(userId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
    val res = libraryCommander.internSystemGeneratedLibraries(userId)

    Ok(res.toString)
  }

  def internAllUserSystemLibraries(startingUserId: Id[User], endingUserId: Id[User]) = AdminHtmlAction.authenticated { implicit request =>
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

  def changeState(libraryId: Id[Library], state: String) = AdminJsonAction.authenticated { request =>
    val libState = state match {
      case LibraryStates.ACTIVE.value => LibraryStates.ACTIVE
      case LibraryStates.INACTIVE.value => LibraryStates.INACTIVE
    }
    db.readWrite(implicit s => libraryRepo.save(libraryRepo.get(libraryId).withState(libState)))
    Ok
  }

  //post request with a list of private/public and active/inactive
  def updateLibraries() = AdminHtmlAction.authenticated { request =>
    def toBoolean(str: String) = str.trim.toInt == 1

    def setToSecret(id: Id[Library])(implicit session: RWSession): Id[User] = {
      val lib = libraryRepo.get(id)
      log.info("updating library %s to secret".format(lib))
      libraryRepo.save(lib.copy(visibility = LibraryVisibility.SECRET))
      log.info("updated library %s".format(lib))
      lib.ownerId
    }

    def setIsActive(id: Id[Library], isActive: Boolean)(implicit session: RWSession): Id[User] = {
      val lib = libraryRepo.get(id)
      log.info("updating bookmark %s with active = %s".format(lib, isActive))
      libraryRepo.save(lib.withActive(isActive))
      log.info("updated bookmark %s".format(lib))
      lib.ownerId
    }

    db.readWrite { implicit s =>
      request.body.asFormUrlEncoded.get foreach {
        case (key, values) =>
          key.split("_") match {
            case Array("active", id) => setIsActive(Id[Library](id.toInt), toBoolean(values.last))
            case Array("secret", id) => {
              if (toBoolean(values.last) == true) {
                setToSecret(Id[Library](id.toInt))
              }
            }
          }
      }
    }
    log.info("updating changed users")
    Redirect(request.request.referer)
  }

}

