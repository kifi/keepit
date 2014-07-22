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
import play.api.libs.json._

import scala.util.{ Success, Failure }

class LibraryController @Inject() (
  db: Database,
  libraryRepo: LibraryRepo,
  userRepo: UserRepo,
  libraryCommander: LibraryCommander,
  actionAuthenticator: ActionAuthenticator,
  clock: Clock,
  implicit val config: PublicIdConfiguration)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def addLibrary() = JsonAction.authenticatedParseJson { request =>
    val addRequest = request.body.as[LibraryAddRequest]

    libraryCommander.addLibrary(addRequest, request.userId) match {
      case Left(LibraryFail(message)) => BadRequest(Json.obj("error" -> message))
      case Right(newLibrary) => Ok(Json.toJson(newLibrary))
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
          case Right(info) => Ok(Json.toJson(info))
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

  def getLibrary(pubId: PublicId[Library]) = JsonAction.authenticatedParseJson { request =>
    val idTry = Library.decodePublicId(pubId)
    idTry match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid id"))
      case Success(id) => Ok(Json.toJson(libraryCommander.getLibraryById(id)))
    }
  }

  def getLibrariesByUser = JsonAction.authenticated { request =>
    val res = for (tuple <- libraryCommander.getLibrariesByUser(request.userId)) yield {
      val lib = tuple._2
      val owner = db.readOnlyReplica { implicit s => userRepo.get(lib.ownerId) }
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
        val inviteList = (request.body \ "pairs").asOpt[Seq[(ExternalId[User], LibraryAccess)]]
        val res = libraryCommander.inviteUsersToLibrary(id, request.userId, inviteList.getOrElse(Seq.empty))
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
}

