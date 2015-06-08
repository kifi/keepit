package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.service.IpAddress
import net.sf.uadetector.UserAgentType
import org.joda.time.DateTime

case class UserIpAddress(
    id: Option[Id[UserIpAddress]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[UserIpAddress],
    userId: Id[User],
    ipAddress: IpAddress,
    // TODO: Turn agentType into an Enum?
    agentType: String) extends ModelWithState[UserIpAddress] {

  def withId(id: Id[UserIpAddress]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object UserIpAddressStates extends States[UserIpAddress] {
}

object UserIpAddress {
}
