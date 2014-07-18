package com.keepit.curator.model

import com.google.inject.{Inject, ImplementedBy}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.DbRepo
import com.keepit.model.User


@ImplementedBy(classOf[CuratorUriInfoRepoImpl])
trait CuratorUriInfoRepo extends DbRepo[CuratorUriInfo]{
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[CuratorUriInfo]
}

@Singleton
class CuratorUriInfoRepoImpl @Inject() {
  val db: DataBaseComponent,
  val clock: Clock)
}

