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
  def getByUser(userId: Id[User])(implicit session: RSession): Seq[UserIpAddress]
  def countByUser(ownerId: Id[User])(implicit session: RSession): Int
  def getSharedIpsByUser(userId: Id[User])(implicit session: RSession): Seq[(IpAddress, Int)]
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

  def getByUser(ownerId: Id[User])(implicit session: RSession): Seq[UserIpAddress] = {
    (for { row <- rows if row.userId === ownerId } yield row).sortBy(_.createdAt.desc).list
  }

  def countByUser(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (r <- rows if r.userId === userId) yield r).length).first
  }

  implicit val getIpIntResult = GetResult(r => (IpAddress(r.<<), r.<< : Int))
  def getSharedIpsByUser(userId: Id[User])(implicit session: RSession): Seq[(IpAddress, Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val result = sql"""select distinct a.ip_address, (select count(distinct b.user_id) from user_ip_addresses b where b.ip_address = a.ip_address) as n from user_ip_addresses a where a.user_id = $userId order by n desc;"""
    result.as[(IpAddress, Int)].list
  }
}
