package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.service.IpAddress
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UserIpAddressRepoImpl])
trait UserIpAddressRepo extends Repo[UserIpAddress] {
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserIpAddress]
  def getMostCommonIpAndCountForUser(userId: Id[User])(implicit session: RSession): (IpAddress, Int)
}

@Singleton
class UserIpAddressRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[UserIpAddress] with UserIpAddressRepo with Logging {

  import db.Driver.simple._
  implicit val IpAddressTypeMapper = MappedColumnType.base[IpAddress, String](_.toString, IpAddress(_))

  type RepoImpl = UserIpAddressTable
  class UserIpAddressTable(tag: Tag) extends RepoTable[UserIpAddress](db, tag, "user_ip_addresses") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def ipAddress = column[IpAddress]("ip_address", O.Nullable)
    def agentType = column[String]("agent_type", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, userId, ipAddress, agentType) <> ((UserIpAddress.apply _).tupled, UserIpAddress.unapply)
  }

  def table(tag: Tag) = new UserIpAddressTable(tag)
  initTable()

  def invalidateCache(model: UserIpAddress)(implicit session: RSession): Unit = {}
  def deleteCache(model: UserIpAddress)(implicit session: RSession): Unit = {}

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserIpAddress] =
    (for (b <- rows if b.userId == userId) yield b).list
}
