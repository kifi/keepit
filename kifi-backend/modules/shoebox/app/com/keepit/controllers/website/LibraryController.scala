package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.common.json.JsonFormatters._
import play.api.libs.functional.syntax._
import play.api.libs.json.{ JsString, Json }

import scala.util.{ Success, Failure }

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  userRepo: UserRepo,
  keepRepo: KeepRepo,
  libraryCommander: LibraryCommander,
  actionAuthenticator: ActionAuthenticator,
  clock: Clock,
  implicit val config: PublicIdConfiguration)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def addLibrary() = JsonAction.authenticatedParseJson { request =>
    val addRequest = request.body.as[LibraryAddRequest]

    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(LibraryFail(message)) => BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) => {
        Ok(Json.toJson(libraryCommander.createFullLibraryInfo(newLibrary)))
      }
    }
  }

  def modifyLibrary(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        val json = request.body
        val newName = (json \ "name").asOpt[String]
        val newDescription = (json \ "description").asOpt[String]
        val newSlug = (json \ "slug").asOpt[String]
        val newVisibility = (json \ "visibility").asOpt[LibraryVisibility]
        val res = libraryCommander.modifyLibrary(id, request.userId, newName, newDescription, newSlug, newVisibility)
        res match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(lib) => {
            val owner = db.readOnlyMaster { implicit s => userRepo.get(lib.ownerId) }
            Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner)))
          }
        }
      }
    }
  }

  def removeLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        libraryCommander.removeLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(success) => Ok(JsString(success))
        }
      }
    }
  }

  def getLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        val lib = libraryCommander.getLibraryById(id)
        Ok(Json.toJson(libraryCommander.createFullLibraryInfo(lib)))
      }
    }
  }

  def getLibrariesByUser = JsonAction.authenticated { request =>
    val res = for (tuple <- libraryCommander.getLibrariesByUser(request.userId)) yield {
      val lib = tuple._2
      val owner = db.readOnlyMaster { implicit s => userRepo.get(lib.ownerId) }
      val info = LibraryInfo.fromLibraryAndOwner(lib, owner)
      Json.obj("info" -> info, "access" -> tuple._1)
    }
    Ok(Json.obj("libraries" -> res))
  }

  def inviteUsersToLibrary(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        val inviteList = (request.body \ "pairs").asOpt[Seq[(ExternalId[User], LibraryAccess)]].getOrElse(Seq.empty)
        val validInviteList = db.readOnlyReplica { implicit s =>
          for (i <- inviteList; user = userRepo.getOpt(i._1) if !user.isEmpty) yield {
            (user.get.id.get, i._2)
          }
        }
        val res = libraryCommander.inviteUsersToLibrary(id, request.userId, validInviteList)
        res match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(info) => {
            val res = info.map { i => Json.obj("user" -> i._1, "access" -> i._2) }
            Ok(Json.toJson(res))
          }
        }
      }
    }
  }

  def joinLibrary(pubId: PublicId[LibraryInvite]) = JsonAction.authenticated { request =>
    val idTry = LibraryInvite.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        val lib = libraryCommander.joinLibrary(id)
        val owner = db.readOnlyMaster { implicit s => userRepo.get(lib.ownerId) }
        Ok(Json.toJson(LibraryInfo.fromLibraryAndOwner(lib, owner)))
      }
    }
  }

  def declineLibrary(pubId: PublicId[LibraryInvite]) = JsonAction.authenticated { request =>
    val idTry = LibraryInvite.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        libraryCommander.declineLibrary(id)
        Ok(JsString("success"))
      }
    }
  }

  def leaveLibrary(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        libraryCommander.leaveLibrary(id, request.userId) match {
          case Left(fail) => BadRequest(Json.obj("error" -> fail.message))
          case Right(_) => Ok(JsString("success"))
        }
      }
    }
  }

  def getKeeps(pubId: PublicId[Library]) = JsonAction.authenticated { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => {
        val keeps = libraryCommander.getKeeps(id)
        val keepInfos = keeps.map(KeepInfo.fromBookmark)
        Ok(Json.toJson(keepInfos))
      }
    }
  }

  def copyKeeps = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid to-libraryId"))
      case Success(toId) => {
        val targetKeeps = db.readOnlyReplica { implicit s => targetKeepsExt.map { keepRepo.getOpt } }
        val (toLib, badKeeps, fail) = libraryCommander.copyKeeps(request.userId, toId, targetKeeps.flatten.toSet)
        val owner = db.readOnlyReplica { implicit s => userRepo.get(toLib.ownerId) }
        fail match {
          case None => Ok(Json.obj("library" -> LibraryInfo.fromLibraryAndOwner(toLib, owner), "failures" -> badKeeps.size))
          case Some(libFail) => BadRequest(Json.obj("error" -> libFail.message))
        }
      }
    }
  }

  def moveKeeps = JsonAction.authenticatedParseJson { request =>
    val json = request.body
    val toPubId = (json \ "to").as[PublicId[Library]]
    val targetKeepsExt = (json \ "keeps").as[Seq[ExternalId[Keep]]]

    Library.decodePublicId(toPubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid to-libraryId"))
      case Success(toId) => {
        val targetKeeps = db.readOnlyReplica { implicit s => targetKeepsExt.map { keepRepo.getOpt } }
        val (toLib, badKeeps, fail) = libraryCommander.moveKeeps(request.userId, toId, targetKeeps.flatten.toSet)
        val owner = db.readOnlyReplica { implicit s => userRepo.get(toLib.ownerId) }
        fail match {
          case None => Ok(Json.obj("library" -> LibraryInfo.fromLibraryAndOwner(toLib, owner), "failures" -> badKeeps.size))
          case Some(libFail) => BadRequest(Json.obj("error" -> libFail.message))
        }
      }
    }
  }

}

