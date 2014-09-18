package com.keepit.common.controller

abstract class UserAPIController(val userActionsHelper: UserActionsHelper) extends ServiceController with UserActions {
}
