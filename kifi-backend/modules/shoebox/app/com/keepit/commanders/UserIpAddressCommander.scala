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
import scala.concurrent.{ ExecutionContext, Future }

class UserIpAddressCommander @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    userIpAddressRepo: UserIpAddressRepo) extends Logging {

  def simplifyUserAgent(userAgent: UserAgent): String = {
    val agentType = userAgent.typeName.toUpperCase()
    if (agentType.isEmpty) "NONE" else agentType
  }

  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent): Unit = SafeFuture {
    if (ip.toString.startsWith("10.")) {
      throw new IllegalArgumentException("IP Addresses of the form 10.x.x.x are internal ec2 addresses and should not be logged")
    }
    val now = DateTime.now()
    val agentType = simplifyUserAgent(userAgent)
    val model = UserIpAddress(None, now, now, UserIpAddressStates.ACTIVE, userId, ip, agentType)
    db.readWrite { implicit session => userIpAddressRepo.save(model) }
  }

  def logUserByRequest[T](request: UserRequest[T]): Unit = {
    val userId = request.userId
    val userAgent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val ip = request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    logUser(userId, IpAddress(ip), userAgent)
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }
}
