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

abstract class LibraryViewController(libraryCommander: LibraryCommander) extends UserActions with Controller {

  def LibraryViewAction(id: PublicId[Library]): ActionFilter[MaybeUserRequest] = new ActionFilter[MaybeUserRequest] {
    def filter[A](input: MaybeUserRequest[A]): Future[Option[Result]] = Future.successful(libraryViewFilter(id, input))
  }

  def LibraryViewAction: ActionFilter[MaybeUserRequest] = new ActionFilter[MaybeUserRequest] {
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
      case userRequest: UserRequest => Some(userRequest.userId)
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
    val cachedResult = Option(LibraryAccessCache.cache.getIfPresent((libraryId, userId, accessToken, hashedPassPhrase)))
    cachedResult match {
      case Some(true) => true
      case Some(false) | None => libraryCommander.canViewLibrary(userId, libraryId, accessToken, hashedPassPhrase)
    }
  }
}

private object LibraryAccessCache {
  val cache: Cache[(Id[Library], Option[Id[User]], Option[String], Option[HashedPassPhrase]), Boolean] =
    CacheBuilder.newBuilder().concurrencyLevel(4).initialCapacity(512).maximumSize(512).expireAfterWrite(10, TimeUnit.SECONDS).build()
}
