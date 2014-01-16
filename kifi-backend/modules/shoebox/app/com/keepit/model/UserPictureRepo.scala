package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy, Provider}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import play.api.libs.json.JsObject

@ImplementedBy(classOf[UserPictureRepoImpl])
trait UserPictureRepo extends Repo[UserPicture] {
  def getByName(id: Id[User], name: String)(implicit session: RSession): Option[UserPicture]
  def getByOrigin(userId: Id[User], origin: UserPictureSource)(implicit session: RSession): Option[UserPicture]
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserPicture]
}

@Singleton
class UserPictureRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[UserPicture] with UserPictureRepo with Logging {

  import db.Driver.Implicit._
  import DBSession._
  import FortyTwoTypeMappers._

  override val table = new RepoTable[UserPicture](db, "user_picture") {
    def name = column[String]("name", O.NotNull)
    def origin = column[UserPictureSource]("origin", O.NotNull)
    def userId = column[Id[User]]("user_id", O.NotNull)
    def attributes = column[JsObject]("attributes", O.Nullable)

    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ name ~ origin ~ state ~ attributes.? <> (UserPicture.apply _, UserPicture.unapply _)
  }

  override def deleteCache(model: UserPicture)(implicit session: RSession): Unit = {}

  def getByName(userId: Id[User], name: String)(implicit session: RSession): Option[UserPicture] = {
    (for (up <- table if up.userId === userId && up.name === name) yield up).firstOption
  }

  def getByOrigin(userId: Id[User], origin: UserPictureSource)(implicit session: RSession): Option[UserPicture] = {
    (for (up <- table if up.userId === userId && up.origin === origin && up.state === UserPictureStates.ACTIVE) yield up).sortBy(d => d.id desc).firstOption
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserPicture] = {
    (for (up <- table if up.userId === userId) yield up).list
  }
}
