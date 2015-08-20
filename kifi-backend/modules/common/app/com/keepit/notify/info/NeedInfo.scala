package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model._

sealed case class NeedInfo[M](kind: String)(id: Id[M])

object NeedInfo {

  val user = NeedInfo[User]("user")
  val library = NeedInfo[Library]("library")
  val userImageUrl = NeedInfo[User]("userImageUrl")
  val keep = NeedInfo[Keep]("keep")
  val libraryUrl = NeedInfo[Library]("libraryUrl")
  val libraryInfo = NeedInfo[Library]("libraryInfo")
  val libraryOwner = NeedInfo[Library]("libraryOwner")
  val organization = NeedInfo[Organization]("organization")

}

