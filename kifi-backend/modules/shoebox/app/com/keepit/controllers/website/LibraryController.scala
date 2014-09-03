package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time.Clock
import com.keepit.model._
import play.api.libs.json.{ JsObject, JsArray, JsString, Json }

import scala.util.{ Success, Failure }

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  libraryMembershipRepo: LibraryMembershipRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  libraryCommander: LibraryCommander,
  actionAuthenticator: ActionAuthenticator,
  clock: Clock,
  implicit val config: PublicIdConfiguration)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def addLibrary() = JsonAction.authenticatedParseJson { request =>
    val addRequest = request.body.as[LibraryAddRequest]

    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(LibraryFail(message)) =>
        BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) =>
        Ok(Json.toJson(libraryCommander.createFullLibraryInfo(newLibrary)))
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) =>
        val json = request.body
        val newName = (json \ "name").asOpt[String]
        val newDescription = (json \ "description").asOpt[String]
        val newSlug = (json \ "slug").asOpt[String]
        val newVisibility = (json \ "visibility").asOpt[LibraryVisibility]
        val res = libraryCommander.modifyLibrary(id, request.userId, newName, newDescription, newSlug, newVisibility)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val (owner, numKeeps) = db.readOnlyMaster { implicit s => (userRepo.get(lib.ownerId), keepRepo.getCountByLibrary(id)) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)))
        }
    }
  }

  def removeLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) =>
        libraryCommander.removeLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(success) => Ok(JsString(success))
        }
    }
  }

  private def canView(userId: Id[User], lib: Library, authToken: Option[String]): Boolean = {
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.getOpt(userId = userId, libraryId = lib.id.get).nonEmpty ||
        (lib.universalLink.nonEmpty && authToken.nonEmpty && lib.universalLink.get == authToken.get)
    }
  }

  def getLibraryById(pubId: PublicId[Library], authToken: Option[String] = None) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) =>
        val lib = db.readOnlyMaster { implicit s => libraryRepo.get(id) }
        if (canView(request.userId, lib, authToken))
          Ok(Json.obj("library" -> Json.toJson(libraryCommander.createFullLibraryInfo(lib))))
        else
          BadRequest(Json.obj("error" -> "invalid access"))
    }
  }

  def getLibraryByPath(userStr: String, slugStr: String, authToken: Option[String] = None) = JsonAction.authenticated { request =>
    // check if str is either a username or externalId
    val ownerOpt = db.readOnlyMaster { implicit s =>
      ExternalId.asOpt[User](userStr) match {
        case Some(eid) => userRepo.getOpt(eid)
        case None => userRepo.getUsername(Username(userStr))
      }
    }
    ownerOpt match {
      case None => BadRequest(Json.obj("error" -> "invalid username"))
      case Some(owner) =>

        db.readOnlyMaster { implicit s =>
          libraryRepo.getBySlugAndUserId(userId = owner.id.get, slug = LibrarySlug(slugStr)) match {
            case None => BadRequest(Json.obj("error" -> "no library found"))
            case Some(lib) =>
              if (canView(request.userId, lib, authToken))
                Ok(Json.obj("library" -> Json.toJson(libraryCommander.createFullLibraryInfo(lib))))
              else
                BadRequest(Json.obj("error" -> "invalid access"))
          }
        }
    }
  }

  def getLibrarySummariesByUser = JsonAction.authenticated { request =>
    val (librariesWithAccess, librariesWithInvites) = libraryCommander.getLibrariesByUser(request.userId)
    // rule out invites that are not duplicate invites to same library (only show library invite with highest access)
    val invitesToShow = librariesWithInvites.groupBy(x => x._2).map { lib =>
      val invites = lib._2.unzip._1
      val highestInvite = invites.sorted.last
      (highestInvite, lib._1)
    }.toSeq

    val libsFollowing = for ((access, library) <- librariesWithAccess) yield {
      val (owner, numKeeps) = db.readOnlyMaster { implicit s =>
        (userRepo.get(library.ownerId), keepRepo.getCountByLibrary(library.id.get))
      }
      val info = LibraryInfo.fromLibraryAndOwner(library, owner, numKeeps)
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> access)
    }
    val libsInvitedTo = for (invite <- invitesToShow) yield {
      val lib = invite._2
      val (owner, numKeeps) = db.readOnlyMaster { implicit s => (userRepo.get(lib.ownerId), keepRepo.getCountByLibrary(lib.id.get)) }
      val info = LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> invite._1.access)
    }
    Ok(Json.obj("libraries" -> libsFollowing, "invited" -> libsInvitedTo))
  }

  def inviteUsersToLibrary(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) =>
        val invites = (request.body \ "invites").as[JsArray].value

        val validInviteList = db.readOnlyMaster { implicit s =>
          invites.map { i =>
            val access = (i \ "access").as[LibraryAccess]
            val id = (i \ "type").as[String] match {
              case "user" if userRepo.getOpt((i \ "id").as[ExternalId[User]]).nonEmpty =>
                Left(userRepo.getOpt((i \ "id").as[ExternalId[User]]).get.id.get)
              case "email" => Right((i \ "id").as[EmailAddress])
            }
            (id, access)
          }
        }

        val res = libraryCommander.inviteUsersToLibrary(id, request.userId, validInviteList)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(info) =>
            val res = info.map {
              case (Left(externalId), access) => Json.obj("user" -> externalId, "access" -> access)
              case (Right(email), access) => Json.obj("email" -> email, "access" -> access)
            }
            Ok(Json.toJson(res))
        }
    }
  }

  def joinLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(libId) =>
        val res = libraryCommander.joinLibrary(request.userId, libId)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val (owner, numKeeps) = db.readOnlyMaster { implicit s => (userRepo.get(lib.ownerId), keepRepo.getCountByLibrary(libId)) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)))
        }
    }
  }

  def declineLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(libId) =>
        libraryCommander.declineLibrary(request.userId, libId)
        Ok(JsString("success"))
    }
  }

  def leaveLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) =>
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], count: Int, offset: Int, authToken: Option[String] = None) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(libraryId) =>

        db.readOnlyReplica { implicit session =>
          if (canView(request.userId, libraryRepo.get(libraryId), authToken)) {
            val take = Math.min(count, 100)
            val numKeeps = keepRepo.getCountByLibrary(libraryId)
            val keepInfos = keepRepo.getByLibrary(libraryId, take, offset).map(KeepInfo.fromKeep)
            Ok(Json.obj("keeps" -> Json.toJson(keepInfos), "count" -> Math.min(take, keepInfos.length), "offset" -> offset, "numKeeps" -> numKeeps))
          } else
            BadRequest(Json.obj("error" -> "invalid access"))
        }
    }
  }

  def getCollaborators(pubId: PublicId[Library], count: Int, offset: Int, authToken: Option[String] = None) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid id"))
      case Success(libraryId) =>

        db.readOnlyReplica { implicit session =>
          if (canView(request.userId, libraryRepo.get(libraryId), authToken)) {
            val take = Math.min(count, 10)
            val memberships = libraryMembershipRepo.pageWithLibraryIdAndAccess(libraryId, take, offset, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT, LibraryAccess.READ_ONLY))
            val (f, c) = memberships.partition(_.access == LibraryAccess.READ_ONLY)
            val followers = f.map(m => basicUserRepo.load(m.userId))
            val collaborators = c.map(m => basicUserRepo.load(m.userId))

            val numF = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, Set(LibraryAccess.READ_ONLY))
            val numC = libraryMembershipRepo.countWithLibraryIdAndAccess(libraryId, Set(LibraryAccess.READ_WRITE, LibraryAccess.READ_INSERT))

            Ok(Json.obj("collaborators" -> Json.toJson(collaborators),
              "followers" -> Json.toJson(followers),
              "numCollaborators" -> numC,
              "numFollowers" -> numF,
              "count" -> take,
              "offset" -> offset))
          } else
            BadRequest(Json.obj("error" -> "invalid access"))
        }
    }
  }

  def copyKeeps = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "dest_invalid_id"))
      case Success(toId) =>
        val targetKeeps = db.readOnlyMaster { implicit s => targetKeepsExt.map(keepRepo.getOpt) }.flatten
        val badKeeps = libraryCommander.copyKeeps(request.userId, toId, targetKeeps)
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> KeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        if (errors.nonEmpty) {
          if (errors.length == targetKeepsExt.length) {
            Ok(Json.obj("success" -> false, "failures" -> errors)) // complete failure
          } else {
            Ok(Json.obj("success" -> "partial", "failures" -> errors)) // partial failure
          }
        } else {
          Ok(Json.obj("success" -> true))
        }
    }
  }

  def moveKeeps = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "dest_invalid_id"))
      case Success(toId) =>
        val targetKeeps = db.readOnlyReplica { implicit s => targetKeepsExt.map { keepRepo.getOpt } }.flatten
        val badKeeps = libraryCommander.moveKeeps(request.userId, toId, targetKeeps)
        val errors = badKeeps.map {
          case (keep, error) =>
            Json.obj(
              "keep" -> KeepInfo.fromKeep(keep),
              "error" -> error.message
            )
        }
        if (errors.nonEmpty) {
          if (errors.length == targetKeepsExt.length) {
            Ok(Json.obj("success" -> false, "failures" -> errors)) // complete failure
          } else {
            Ok(Json.obj("success" -> "partial", "failures" -> errors)) // partial failure
          }
        } else {
          Ok(Json.obj("success" -> true))
        }
    }
  }

}

