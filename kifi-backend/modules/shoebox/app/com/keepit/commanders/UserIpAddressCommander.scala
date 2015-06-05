package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.service.IpAddress
import com.keepit.model.{ UserIpAddressStates, User, UserIpAddress, UserIpAddressRepo }
import org.joda.time.DateTime
import com.keepit.common.net.UserAgent

class UserIpAddressCommander @Inject() (
    db: Database,
    userIpAddressRepo: UserIpAddressRepo) extends Logging {

  def simplifyUserAgent(userAgent: UserAgent): String = {
    // TODO: Do we want to know more than this?
    val agentType = userAgent.typeName.toUpperCase()
    if (!agentType.isEmpty) agentType else "NONE"
  }
  def logUser(userId: Id[User], ip: IpAddress, userAgent: UserAgent): Unit = {
    val now = DateTime.now()
    val agentType = simplifyUserAgent(userAgent)
    val model = UserIpAddress(None, now, now, UserIpAddressStates.ACTIVE, userId, ip, agentType)
    println("[RPB] logUser: " + model)
    db.readWrite { implicit session => userIpAddressRepo.save(model) }
  }

  def totalNumberOfLogs(): Int = {
    db.readOnlyReplica { implicit session => userIpAddressRepo.count }
  }
}