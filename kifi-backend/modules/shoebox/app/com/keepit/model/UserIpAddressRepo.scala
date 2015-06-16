package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.service.IpAddress
import com.keepit.common.time.Clock
import org.joda.time.DateTime

import scala.slick.jdbc.GetResult

@ImplementedBy(classOf[UserIpAddressRepoImpl])
trait UserIpAddressRepo extends Repo[UserIpAddress] {
  def saveIfNew(model: UserIpAddress)(implicit session: RWSession): UserIpAddress
  def countByUser(userId: Id[User])(implicit session: RSession): Int
  def getByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[UserIpAddress]
  def getUsersFromIpAddressSince(ip: IpAddress, time: DateTime)(implicit session: RSession): Seq[Id[User]]
  def findSharedIpsByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[(IpAddress, Id[User])]
  def findIpClustersSince(time: DateTime, limit: Int)(implicit session: RSession): Seq[IpAddress]
}

@Singleton
class UserIpAddressRepoImpl @Inject() (
  val db: DataBaseComponent,
  userIpAddressCache: UserIpAddressCache,
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

  override def deleteCache(model: UserIpAddress)(implicit session: RSession): Unit = {
    userIpAddressCache.remove(UserIpAddressKey(model.userId))
  }

  override def invalidateCache(model: UserIpAddress)(implicit session: RSession) = {
    userIpAddressCache.set(UserIpAddressKey(model.userId), model)
  }

  def saveIfNew(model: UserIpAddress)(implicit session: RWSession): UserIpAddress = {
    userIpAddressCache.get(UserIpAddressKey(model.userId)) match {
      case None => save(model)
      case Some(oldModel) =>
        if (model.createdAt.minusMinutes(30).isBefore(oldModel.createdAt)) {
          oldModel // within 30 minutes of old model, ignore
        } else {
          save(model)
        }
    }
  }

  def getByUser(ownerId: Id[User], limit: Int)(implicit session: RSession): Seq[UserIpAddress] = {
    (for { row <- rows if row.userId === ownerId } yield row).sortBy(_.createdAt.desc).take(limit).list
  }

  def countByUser(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (r <- rows if r.userId === userId) yield r).length).first
  }

  def getUsersFromIpAddressSince(ip: IpAddress, time: DateTime)(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val result = sql"""select distinct user_id
                       from user_ip_addresses
                       where created_at > $time and ip_address = ${ip.ip}"""
    result.as[Id[User]].list
  }

  implicit val getSharedIpResult = GetResult(r => (IpAddress(r.<<), r.<< : Id[User]))
  def findSharedIpsByUser(userId: Id[User], limit: Int)(implicit session: RSession): Seq[(IpAddress, Id[User])] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val result = sql"""select distinct a.ip_address, b.user_id
                       from user_ip_addresses a, user_ip_addresses b
                       where a.user_id = $userId and b.ip_address = a.ip_address and b.user_id != a.user_id
                       limit $limit"""
    result.as[(IpAddress, Id[User])].list
  }

  implicit val getIpAddressResult = GetResult(r => (IpAddress(r.<<)))
  def findIpClustersSince(time: DateTime, limit: Int)(implicit session: RSession): Seq[IpAddress] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val result = sql"""select ip_address
                       from user_ip_addresses
                       where created_at > $time
                       group by ip_address
                       order by count(distinct user_id) desc
                       limit $limit;"""
    result.as[IpAddress].list
  }
}
