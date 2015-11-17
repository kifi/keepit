package com.keepit.model

import com.keepit.common.reflection.Enumerator

sealed abstract class UserPermission(val value: String)

object UserPermission extends Enumerator[UserPermission] {
  case object CREATE_SLACK_INTEGRATION extends UserPermission("create_slack_integration")
  val all: Set[UserPermission] = _all.toSet
}
