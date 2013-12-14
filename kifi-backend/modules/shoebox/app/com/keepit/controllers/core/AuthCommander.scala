package com.keepit.controllers.core

import com.google.inject.Inject
import play.api.mvc._
import securesocial.core.SecureSocial
import play.api.mvc.SimpleResult
import play.api.http.HeaderNames
import com.keepit.common.time.Clock

class AuthCommander @Inject() (
  clock: Clock
) extends HeaderNames with Results {

  def handleAuthResult(link:String, request:Request[_], res:SimpleResult[_])(f : => (Seq[Cookie], Session) => Result) = {
     if (res.header.status == 303) {
       val resCookies = res.header.headers.get(SET_COOKIE).map(Cookies.decode).getOrElse(Seq.empty)
       val resSession = Session.decodeFromCookie(resCookies.find(_.name == Session.COOKIE_NAME))
       val newSession = if (link != "") {
         resSession - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link) // removal of OriginalUrlKey might be redundant
       } else resSession
       f(resCookies, newSession)
    } else res
  }

}
