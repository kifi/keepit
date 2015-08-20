package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, Library, User }

sealed trait NeedInfo[+A]

trait PossibleNeeds {

  case class NeedUser(id: Id[User]) extends NeedInfo[User]
  case class NeedLibrary(id: Id[Library]) extends NeedInfo[Library]
  case class NeedUserImageUrl(id: Id[User]) extends NeedInfo[String]
  case class NeedKeep(id: Id[Keep]) extends NeedInfo[Keep]
  case class NeedLibraryUrl(id: Id[Library]) extends NeedInfo[String]

  def user(id: Id[User]): NeedInfo[User] = NeedUser(id)
  def library(id: Id[Library]): NeedInfo[Library] = NeedLibrary(id)
  def userImageUrl(id: Id[User]): NeedInfo[String] = NeedUserImageUrl(id)
  def keep(id: Id[Keep]): NeedInfo[Keep] = NeedKeep(id)
  def libraryUrl(id: Id[Library]): NeedInfo[String] = NeedLibraryUrl(id)

}

object NeedInfo extends PossibleNeeds

