package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.UserRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.common.service.IpAddress
import com.keepit.model.{ User, UserIpAddress, UserIpAddressRepo, UserIpAddressStates }
import org.joda.time.DateTime

import scala.concurrent.{ Future, ExecutionContext }

class UserIpAddressCommander @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    userIpAddressRepo: UserIpAddressRepo) extends Logging {

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent): Future[Unit] = SafeFuture {
    if (ip.ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException("IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged")
    }
    val now = DateTime.now()
    val agentType = simplifyUserAgent(userAgent)
    if (agentType == "NONE") {
      log.info("[RPB] Could not parse an agent type out of: " + userAgent)
    }
    val model = UserIpAddress(None, now, now, UserIpAddressStates.ACTIVE, userId, ip, agentType)
    db.readWrite { implicit session => userIpAddressRepo.save(model) }
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val raw_ip_string = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val ip = IpAddress(raw_ip_string.split(",").head)
    logUser(userId, ip, userAgent)
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }

  def countByUser(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.countByUser(userId) }
  }
  def getByUser(userId: Id[User], limit: Int): Seq[UserIpAddress] = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.getByUser(userId, limit) }
  }

  def kvPairsToMap[A, B](kvs: Seq[(A, B)]): Map[A, Seq[B]] = {
    kvs.groupBy(_._1).mapValues(_.map(_._2))
  }
  def findSharedIpsByUser(userId: Id[User], limit: Int): Map[IpAddress, Seq[Id[User]]] = {
    val sharedIps = db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findSharedIpsByUser(userId, limit)
    }
    kvPairsToMap(sharedIps)
  }
  def findIpClustersSince(time: DateTime, limit: Int): Seq[(IpAddress, Int, Id[User])] = {
    db.readOnlyReplica { implicit session =>
      userIpAddressRepo.findIpClustersSince(time, limit)
    }
  }
}
