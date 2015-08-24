package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model._

sealed case class NeedInfo[M, R](kind: String, id: Id[M]) {

  def lookup(subset: DbSubset): R = subset.lookup(kind, id).asInstanceOf[R]

}

object NeedInfo {

  def apply[M, R](kind: String): (Id[M]) => NeedInfo[M, R] = (id: Id[M]) => NeedInfo(kind, id)

  val user = NeedInfo[User, User]("user")
  val library = NeedInfo[Library, Library]("library")
  val userImageUrl = NeedInfo[User, String]("userImageUrl")
  val keep = NeedInfo[Keep, Keep]("keep")
  val libraryUrl = NeedInfo[Library, String]("libraryUrl")
  val libraryInfo = NeedInfo[Library, LibraryNotificationInfo]("libraryInfo")
  val libraryOwner = NeedInfo[Library, User]("libraryOwner")
  val organization = NeedInfo[Organization, Organization]("organization")
  val organizationInfo = NeedInfo[Organization, OrganizationNotificationInfo]("organizationInfo")

}

