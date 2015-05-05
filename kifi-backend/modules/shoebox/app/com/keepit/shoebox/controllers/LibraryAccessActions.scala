package com.keepit.shoebox.controllers

import java.util.concurrent.TimeUnit

import com.google.common.cache.{ Cache, CacheBuilder }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.model.{ LibraryAccess, HashedPassPhrase, Library, User }
import play.api.libs.json.Json
import play.api.mvc.{ ActionFilter, Controller, Result }

import scala.concurrent.Future
import scala.util.Success

trait LibraryAccessActions {
  self: UserActions with Controller =>

  val publicIdConfig: com.keepit.common.crypto.PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val libraryCommander: LibraryCommander

  def LibraryViewAction(id: PublicId[Library]) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(lookupViewAccess(id, input))
  }

  def LibraryWriteAction(id: PublicId[Library]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupWriteAccess(id, input))
  }

  def LibraryOwnerAction(id: PublicId[Library]): ActionFilter[UserRequest] = new ActionFilter[UserRequest] {
    def filter[A](input: UserRequest[A]): Future[Option[Result]] = Future.successful(lookupOwnerAccess(id, input))
  }

  // Helpers:

  private def lookupViewAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, userIdOpt, accessToken, hashedPassPhrase)) =>
        val access = libraryCommander.canViewLibrary(userIdOpt, libraryId, accessToken, hashedPassPhrase)
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
      case Some((libraryId, Some(userId), accessToken, hashedPassPhrase)) =>
        libraryCommander.userAccess(userId, libraryId, None) match {
          case Some(LibraryAccess.OWNER) | Some(LibraryAccess.READ_WRITE) =>
            None
          case _ =>
            Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def lookupOwnerAccess[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]) = {
    parseRequest(libraryPubId, input) match {
      case Some((libraryId, Some(userId), accessToken, hashedPassPhrase)) =>
        libraryCommander.userAccess(userId, libraryId, None) match {
          case Some(LibraryAccess.OWNER) =>
            None
          case _ =>
            Some(Forbidden(Json.obj("error" -> "permission_denied")))
        }
      case _ =>
        Some(BadRequest(Json.obj("error" -> "invalid_id")))
    }
  }

  private def parseRequest[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]): Option[(Id[Library], Option[Id[User]], Option[String], Option[HashedPassPhrase])] = {
    val userIdOpt: Option[Id[User]] = input match {
      case userRequest: UserRequest[A] => Some(userRequest.userId)
      case _ => None
    }

    val libIdOpt = Library.decodePublicId(libraryPubId).toOption
    libIdOpt.map { libId =>

      val (cookieLibraryId, hashedPassPhrase) = input.session.get("library_access").flatMap { libraryAccessCookie =>
        libraryCommander.getLibraryIdAndPassPhraseFromCookie(libraryAccessCookie).map { r =>
          (Some(r._1), Some(r._2))
        }
      }.getOrElse((None, None))

      if (cookieLibraryId.isEmpty || (cookieLibraryId.isDefined && cookieLibraryId.get != libId)) {
        (libId, userIdOpt, None, None)
      } else {
        input.getQueryString("authToken") match {
          case Some(accessToken) =>
            (libId, userIdOpt, Some(accessToken), hashedPassPhrase)
          case None =>
            (libId, userIdOpt, None, None)
        }
      }
    }
  }
}
