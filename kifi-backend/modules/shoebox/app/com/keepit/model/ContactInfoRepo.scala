package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.db.{State, Id}
import com.keepit.common.time.Clock
import scala.slick.util.CloseableIterator

// TODO: move this outside of shoebox

@ImplementedBy(classOf[ContactInfoRepoImpl])
trait ContactInfoRepo extends Repo[ContactInfo] {
  def getByUserId(userId: Id[User], maxRows: Int = 100)(implicit session: RSession): CloseableIterator[ContactInfo]
}

@Singleton
class ContactInfoRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ContactInfo] with ContactInfoRepo {
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[ContactInfo](db, "contact_info") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def email = column[String]("email", O.NotNull)
    def origin = column[String]("origin", O.Nullable)
    def name = column[String]("name", O.Nullable)
    def firstName = column[String]("first_name", O.Nullable)
    def lastName = column[String]("last_name", O.Nullable)
    def pictureUrl = column[String]("picture_url", O.Nullable)
    def parentId = column[Id[ContactInfo]]("contact_info", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ email ~ origin.? ~ name.? ~ firstName.? ~ lastName.? ~ pictureUrl.? ~ parentId.? <> (ContactInfo.apply _, ContactInfo.unapply _)
  }

  def getByUserId(userId: Id[User], maxRows:Int)(implicit session: RSession): CloseableIterator[ContactInfo] =
    (for(f <- table if f.userId === userId) yield f).elementsTo(maxRows)
}

