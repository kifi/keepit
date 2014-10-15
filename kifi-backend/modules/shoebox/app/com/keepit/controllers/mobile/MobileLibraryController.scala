package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.social.BasicUserRepo
import com.keepit.controllers.mobile.ImplicitHelper._
import com.keepit.model._
import com.keepit.shoebox.controllers.LibraryAccessActions
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsObject, JsString, Json }

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class MobileLibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  keepRepo: KeepRepo,
  basicUserRepo: BasicUserRepo,
  keepsCommander: KeepsCommander,
  val libraryCommander: LibraryCommander,
  val userActionsHelper: UserActionsHelper,
  val publicIdConfig: PublicIdConfiguration,
  implicit val config: PublicIdConfiguration)
    extends UserActions with LibraryAccessActions with ShoeboxServiceController {

  def getLibraryById(pubId: PublicId[Library]) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val id = Library.decodePublicId(pubId).get
    libraryCommander.getLibraryById(request.userIdOpt, id) map {
      case (libInfo, accessStr) => Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
    }
  }

  def getLibraryByPath(userStr: String, slugStr: String) = MaybeUserAction.async { request =>
    libraryCommander.getLibraryWithUsernameAndSlug(userStr, LibrarySlug(slugStr)) match {
      case Right(library) =>
        LibraryViewAction(Library.publicId(library.id.get)).invokeBlock(request, { _: MaybeUserRequest[_] =>
          request.userIdOpt.map { userId => libraryCommander.updateLastView(userId, library.id.get) }
          libraryCommander.createFullLibraryInfo(request.userIdOpt, library).map { libInfo =>
            val accessStr = request.userIdOpt.map { userId =>
              libraryCommander.getAccessStr(userId, library.id.get)
            }.flatten.getOrElse {
              "none"
            }
            Ok(Json.obj("library" -> Json.toJson(libInfo), "membership" -> accessStr))
          }
        })
      case Left((respCode, msg)) =>
        Future.successful(Status(respCode)(Json.obj("error" -> msg)))
    }
  }

  def getLibrarySummariesByUser = UserAction { request =>
    val (librariesWithMemberships, librariesWithInvites) = libraryCommander.getLibrariesByUser(request.userId)
    // rule out invites that are not duplicate invites to same library (only show library invite with highest access)
    val invitesToShow = librariesWithInvites.groupBy(x => x._2).map { lib =>
      val invites = lib._2.unzip._1
      val highestInvite = invites.sorted.last
      (highestInvite, lib._1)
    }.toSeq

    val libsFollowing = for ((mem, library) <- librariesWithMemberships) yield {
      val (owner, numKeeps) = db.readOnlyMaster { implicit s =>
        (basicUserRepo.load(library.ownerId), keepRepo.getCountByLibrary(library.id.get))
      }
      val info = LibraryInfo.fromLibraryAndOwner(library, owner, numKeeps)
      val memInfo = if (mem.lastViewed.nonEmpty) Json.obj("access" -> mem.access, "lastViewed" -> mem.lastViewed) else Json.obj("access" -> mem.access)
      Json.toJson(info).as[JsObject] ++ memInfo
    }
    val libsInvitedTo = for (invitePair <- invitesToShow) yield {
      val invite = invitePair._1
      val lib = invitePair._2
      val (inviteOwner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(invite.ownerId), keepRepo.getCountByLibrary(lib.id.get)) }
      val info = LibraryInfo.fromLibraryAndOwner(lib, inviteOwner, numKeeps)
      Json.toJson(info).as[JsObject] ++ Json.obj("access" -> invite.access)
    }
    Ok(Json.obj("libraries" -> libsFollowing, "invited" -> libsInvitedTo))
  }

  def joinLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>

        val res = libraryCommander.joinLibrary(request.userId, libId)
        res match {
          case Left(fail) =>
            BadRequest(Json.obj("error" -> fail.message))
          case Right(lib) =>
            val (owner, numKeeps) = db.readOnlyMaster { implicit s => (basicUserRepo.load(lib.ownerId), keepRepo.getCountByLibrary(libId)) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner, numKeeps)))
        }
    }
  }

  def declineLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(libId) =>
        libraryCommander.declineLibrary(request.userId, libId)
        Ok(JsString("success"))
    }
  }

  def leaveLibrary(pubId: PublicId[Library]) = UserAction { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(id) =>
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
    }
  }

  def getKeeps(pubId: PublicId[Library], count: Int, offset: Int) = (MaybeUserAction andThen LibraryViewAction(pubId)).async { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) =>
        Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(libraryId) =>
        val take = Math.min(count, 30)
        val (keeps, numKeeps) = libraryCommander.getKeeps(libraryId, take, offset)
        val keepInfosF = keepsCommander.decorateKeepsIntoKeepInfos(request.userIdOpt, keeps)

        keepInfosF.map { keepInfos =>
          Ok(Json.obj("keeps" -> Json.toJson(keepInfos), "count" -> Math.min(take, keepInfos.length), "offset" -> offset, "numKeeps" -> numKeeps))
        }
    }
  }

}

private object ImplicitHelper {
  implicit class PutUserIdOptInMaybeAuthReq(val request: MaybeUserRequest[_]) extends AnyVal {
    def userIdOpt: Option[Id[User]] = request match {
      case u: UserRequest[_] => Some(u.userId)
      case _ => None
    }
  }
}
