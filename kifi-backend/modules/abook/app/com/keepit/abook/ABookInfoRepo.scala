package com.keepit.abook

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.model.{ OAuth2Token, ABookOriginType, User, ABookInfo }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.db.{ ExternalId, Model, Id }
import com.keepit.common.db.slick.DBSession.RSession
import org.joda.time.DateTime

@ImplementedBy(classOf[ABookInfoRepoImpl])
trait ABookInfoRepo extends Repo[ABookInfo] {
  def getById(id: Id[ABookInfo])(implicit session: RSession): Option[ABookInfo]
  def getByExternalId(externalId: ExternalId[ABookInfo])(implicit session: RSession): Option[ABookInfo]
  def getByUserIdAndABookId(userId: Id[User], id: Id[ABookInfo])(implicit session: RSession): Option[ABookInfo]
  def findByUserIdOriginAndOwnerId(userId: Id[User], origin: ABookOriginType, ownerId: Option[String])(implicit session: RSession): Option[ABookInfo]
  def findByUserIdAndOrigin(userId: Id[User], origin: ABookOriginType)(implicit session: RSession): Seq[ABookInfo]
  def findByUserId(userId: Id[User])(implicit session: RSession): Seq[ABookInfo]
  def isOverdue(id: Id[ABookInfo], due: DateTime = currentDateTime)(implicit session: RSession): Boolean
}

class ABookInfoRepoImpl @Inject() (val db: DataBaseComponent, val clock: Clock) extends DbRepo[ABookInfo] with ABookInfoRepo with Logging {

  import db.Driver.simple._

  type RepoImpl = ABookTable
  class ABookTable(tag: Tag) extends RepoTable[ABookInfo](db, tag, "abook_info") with ExternalIdColumn[ABookInfo] {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def origin = column[ABookOriginType]("origin", O.NotNull)
    def ownerId = column[String]("owner_id")
    def ownerEmail = column[String]("owner_email")
    def rawInfoLoc = column[String]("raw_info_loc")
    def oauth2TokenId = column[Id[OAuth2Token]]("oauth2_token_id")
    def numContacts = column[Int]("num_contacts", O.Nullable)
    def numProcessed = column[Int]("num_processed", O.Nullable)
    def * = (id.?, createdAt, updatedAt, externalId, state, userId, origin, ownerId.?, ownerEmail.?, rawInfoLoc.?, oauth2TokenId.?, numContacts.?, numProcessed.?) <> ((ABookInfo.apply _).tupled, ABookInfo.unapply _)
  }

  def table(tag: Tag) = new ABookTable(tag)

  override def deleteCache(model: ABookInfo)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: ABookInfo)(implicit session: RSession): Unit = {}

  def getById(id: Id[ABookInfo])(implicit session: RSession): Option[ABookInfo] = {
    (for { c <- rows if c.id === id } yield c).firstOption
  }

  def getByExternalId(externalId: ExternalId[ABookInfo])(implicit session: RSession): Option[ABookInfo] = {
    (for { c <- rows if c.externalId === externalId } yield c).firstOption
  }

  def getByUserIdAndABookId(userId: Id[User], id: Id[ABookInfo])(implicit session: RSession): Option[ABookInfo] = {
    (for { c <- rows if c.userId === userId && c.id === id } yield c).firstOption
  }

  def findByUserIdOriginAndOwnerId(userId: Id[User], origin: ABookOriginType, ownerId: Option[String])(implicit session: RSession): Option[ABookInfo] = {
    val q = for { c <- rows if c.userId === userId && c.origin === origin && c.ownerId === ownerId } yield c // assumption: NULL === None
    q.firstOption
  }

  def findByUserIdAndOrigin(userId: Id[User], origin: ABookOriginType)(implicit session: RSession): Seq[ABookInfo] = {
    val q = for { c <- rows if c.userId === userId && c.origin === origin } yield c
    q.list
  }

  def findByUserId(userId: Id[User])(implicit session: RSession): Seq[ABookInfo] = {
    val q = for { c <- rows if c.userId === userId } yield c
    q.list
  }

  def isOverdue(id: Id[ABookInfo], due: DateTime)(implicit session: RSession): Boolean = {
    (for { c <- rows if c.id === id && c.updatedAt >= due } yield c).firstOption.isDefined
  }
}
