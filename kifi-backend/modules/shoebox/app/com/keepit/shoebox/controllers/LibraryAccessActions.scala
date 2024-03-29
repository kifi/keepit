package com.keepit.shoebox.controllers

import com.keepit.commanders.{ LibraryAccessCommander }
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.model.{ LibraryAccess, Library, User }
import play.api.libs.json.Json
import play.api.mvc.{ ActionFilter, Controller, Result }

import scala.concurrent.Future

trait LibraryAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: com.keepit.common.crypto.PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val libraryAccessCommander: LibraryAccessCommander

  // todo(Léo): find someone smart enough to make LibraryViewAction preserve subtype and remove LibraryViewUserAction
  def LibraryViewAction(id: PublicId[Library]) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewAccess(id, input))
  }

  def LibraryViewUserAction(id: PublicId[Library]) = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewAccess(id, input))
  }

  def LibraryWriteAction(id: PublicId[Library]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupWriteAccess(id, input))
  }

  def LibraryWriteOrJoinAction(id: PublicId[Library]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupWriteOrJoinAccess(id, input))
  }

  def LibraryOwnerAction(id: PublicId[Library]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupOwnerAccess(id, input))
  }

  // Helpers:

  private def lookupViewAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, userIdOpt, accessToken)) =>
        val access = libraryAccessCommander.canViewLibrary(userIdOpt, libraryId, accessToken)
        if (access) {
          None
        } else {
          Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def lookupWriteAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, Some(userId), accessToken)) =>
        if (libraryAccessCommander.canModifyLibrary(libraryId, userId)) None
        else Some(Forbidden(Json.obj("error" -> "permission_denied")))
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def lookupWriteOrJoinAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, Some(userId), accessToken)) =>
        if (libraryAccessCommander.ensureUserCanWriteTo(userId, Set(libraryId))) {
          None
        } else {
          Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def lookupOwnerAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, Some(userId), accessToken)) =>
        libraryAccessCommander.userAccess(userId, libraryId) match {
          case Some(LibraryAccess.OWNER) =>
            None
          case _ =>
            Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def parseRequest[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]): Option[(Id[Library], Option[Id[User]], Option[String])] = {
    val userIdOpt: Option[Id[User]] = input match {
      case userRequest: UserRequest[A] => Some(userRequest.userId)
      case _ => None
    }

    val libIdOpt = Library.decodePublicId(libraryPubId).toOption
    libIdOpt.map { libId =>
      (libId, userIdOpt, input.getQueryString("authToken"))
    }
  }
}
