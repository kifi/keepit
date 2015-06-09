package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.service.IpAddress
import com.keepit.common.time.Clock

import scala.slick.jdbc.GetResult

@ImplementedBy(classOf[UserIpAddressRepoImpl])
trait UserIpAddressRepo extends Repo[UserIpAddress] {
  def countByUser(userId: Id[User])(implicit session: RSession): Int
  def getByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[UserIpAddress]
  def getSharedIpsByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[(IpAddress, Id[User])]
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
    def userId = column[Id[User]]("user_id", O.NotNull)
    def ipAddress = column[IpAddress]("ip_address", O.NotNull)
    def agentType = column[String]("agent_type", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, userId, ipAddress, agentType) <> ((UserIpAddress.apply _).tupled, UserIpAddress.unapply)
  }

  def table(tag: Tag) = new UserIpAddressTable(tag)
  initTable()

  def invalidateCache(model: UserIpAddress)(implicit session: RSession): Unit = {}
  def deleteCache(model: UserIpAddress)(implicit session: RSession): Unit = {}

  def getByUser(ownerId: Id[User], limit: Int)(implicit session: RSession): Seq[UserIpAddress] = {
    (for { row <- rows if row.userId === ownerId } yield row).sortBy(_.createdAt.desc).take(limit).list
  }

  def countByUser(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (r <- rows if r.userId === userId) yield r).length).first
  }

  implicit val getSharedIpResult = GetResult(r => (IpAddress(r.<<), r.<< : Id[User]))
  def getSharedIpsByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[(IpAddress, Id[User])] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val result = sql"""select distinct a.ip_address, b.user_id
                       from user_ip_addresses a, user_ip_addresses b
                       where a.user_id = $userId and b.ip_address = a.ip_address and b.user_id != a.user_id
                       limit $limit"""
    result.as[(IpAddress, Id[User])].list
  }
}
