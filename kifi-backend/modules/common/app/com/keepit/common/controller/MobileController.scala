package com.keepit.common.controller

import play.api.mvc._

class MobileController(override val actionAuthenticator: ActionAuthenticator) extends Controller with JsonActions

