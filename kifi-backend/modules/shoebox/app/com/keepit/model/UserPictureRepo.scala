package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy, Provider}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import scala.util.Random

@ImplementedBy(classOf[UserPictureRepoImpl])
trait UserPictureRepo extends Repo[UserPicture] {
  def generateNewFilename: String

  def getByName(id: Id[User], name: String)(implicit session: RSession): Option[UserPicture]
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
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ name ~ origin ~ state <> (UserPicture.apply _, UserPicture.unapply _)
  }


  /*
   * If we *ever* have a collision on `name`, Jared Jacobs (signed: 𝐽𝑎𝑟𝑒𝑑 𝐽𝑎𝑐𝑜𝑏𝑠) owes whoever claims this first $10,000
   * Witness: Andrew Conner (signed: 𝒜𝓃𝒹𝓇ℯ𝓌 𝒞ℴ𝓃𝓃ℯ𝓇). Oct 21, 2013.
   */

  def generateNewFilename: String = {
    def nextChar: Char = {
      val rnd = Random.nextInt(62) + 48
      if(rnd <= 57)
        rnd.toChar
      else if(rnd <= 83)
        (rnd+7).toChar
      else
        (rnd+13).toChar
    }
    Seq.fill(5)(nextChar).mkString
  }

  def getByName(userId: Id[User], name: String)(implicit session: RSession): Option[UserPicture] = {
    (for (up <- table if up.userId === userId && up.name === name) yield up).firstOption
  }

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserPicture] = {
    (for (up <- table if up.userId === userId) yield up).list
  }
}
