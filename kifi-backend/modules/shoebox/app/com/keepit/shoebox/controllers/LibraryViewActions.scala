package com.keepit.shoebox.controllers

import java.util.concurrent.TimeUnit

import com.google.common.cache.{ Cache, CacheBuilder }
import com.keepit.commanders.LibraryCommander
import com.keepit.common.controller.{ MaybeUserRequest, UserActions, UserRequest }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.model.{ HashedPassPhrase, Library, User }
import play.api.mvc.{ ActionFilter, Controller, Result }

import scala.concurrent.Future
import scala.util.Success

trait LibraryViewActions { self: UserActions with Controller =>

  val publicIdConfig: com.keepit.common.crypto.PublicIdConfiguration
  implicit private val implicitPublicId = publicIdConfig
  val libraryCommander: LibraryCommander

  def LibraryViewAction(id: PublicId[Library]) = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(libraryViewFilter(id, input))
  }

  def LibraryViewAction = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = {
      input.getQueryString("libraryId") match {
        case Some(libId) => Future.successful(libraryViewFilter(PublicId[Library](libId), input))
        case None => Future.successful(Some(Forbidden))
      }
    }
  }

  // Helpers:

  private def libraryViewFilter[A](libraryPubId: PublicId[Library], input: MaybeUserRequest[A]): Option[Result] = {
    val userIdOpt: Option[Id[User]] = input match {
      case userRequest: UserRequest[A] => Some(userRequest.userId)
      case _ => None
    }

    val libIdOpt = Library.decodePublicId(libraryPubId).toOption
    libIdOpt match {
      case Some(libId) =>
        val (cookieLibraryId, hashedPassPhrase) = input.session.get("library_access").flatMap { s =>
          val a = s.split('/')
          (a.headOption, a.tail.headOption) match {
            case (Some(l), Some(p)) =>
              Library.decodePublicId(PublicId[Library](l)) match {
                case Success(lid) => Some(Some(lid), Some(HashedPassPhrase(p)))
                case _ => None
              }
            case _ => None
          }
        }.getOrElse((None, None))

        val hasAccess = if (cookieLibraryId.isEmpty || (cookieLibraryId.isDefined && cookieLibraryId.get != libId)) {
          lookupAccess(libId, userIdOpt, None, None)
        } else {
          input.getQueryString("accessToken") match {
            case Some(accessToken) =>
              lookupAccess(libId, userIdOpt, Some(accessToken), hashedPassPhrase)
            case None =>
              lookupAccess(libId, userIdOpt, None, None)
          }

        }
        if (hasAccess) {
          None
        } else {
          Some(Forbidden)
        }
      case None =>
        Some(Forbidden)
    }
  }

  private def lookupAccess(libraryId: Id[Library], userId: Option[Id[User]], accessToken: Option[String], hashedPassPhrase: Option[HashedPassPhrase]): Boolean = {
    libraryCommander.canViewLibrary(userId, libraryId, accessToken, hashedPassPhrase)
  }
}

