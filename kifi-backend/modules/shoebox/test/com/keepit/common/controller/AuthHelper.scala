package com.keepit.common.controller

import com.keepit.common.logging.Logging
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.social.{SocialId, SocialNetworks}
import com.keepit.model._
import com.keepit.common.db.slick._
import com.keepit.social.SocialNetworks.FACEBOOK

import securesocial.core._
import play.api.mvc.AnyContentAsJson
import play.api.Application

import play.api.libs.json._

import play.api.mvc._
import play.api.test.FakeRequest
import play.api.test.Helpers._

class AuthHelper(db: Database, socialUserInfoRepo: SocialUserInfoRepo, userRepo: UserRepo) extends Logging {

  def createUser(id: Int = 1): (User, Cookie) = db.readWrite { implicit s =>
    val user = userRepo.save(User(firstName = "A" + id, lastName = "L" + id))
    val su = SocialUser(IdentityId(id.toString, "facebook"), "A", id.toString, "A " + id, Some(s"a${id}@gmail.com"),
      Some(s"http://www.fb.com/me/${id}"), AuthenticationMethod.OAuth2, None, Some(OAuth2Info(accessToken = s"A${id}")), None)
    val sui = socialUserInfoRepo.save(SocialUserInfo(userId = user.id, fullName = "F" + id, socialId = SocialId(id.toString),
      networkType = FACEBOOK, credentials = Some(su)))
    log.info("=========================================")
    log.info(sui.toString)
    log.info(socialUserInfoRepo.get(sui.id.get).toString)
    log.info(socialUserInfoRepo.get(SocialId(id.toString), SocialNetworks.FACEBOOK).toString)
    log.info("=========================================")
    (user, createCookie(su))
  }

  def createCookie(su: SocialUser): Cookie = Authenticator.create(su) match {
    case Right(v) =>
      val c = v.toCookie
      log.info(s"cookie = [$c]")
      c
    case Left(e) => throw e
  }

  def createPostRequest(path: String, cookie: Cookie): FakeRequest[AnyContentAsJson] = FakeRequest("POST", path)
      .withCookies(cookie)
      .withJsonBody(JsObject(Seq("agent" -> JsString("test agent"), "version" -> JsString("0.0.0"))))
  def execRequest(req: FakeRequest[AnyContentAsJson]): Result = route(req).get
  def execRequest(app: Application, path: String, cookie: Cookie): Result = {
    val res = route(app, createPostRequest(path, cookie))
    log.info(s"req on path $path with cookie [$cookie] res = $res")
    res.get
  }
  def ok(res: Result) = if (status(res) != 200) throw new IllegalStateException(s"illegal state: ${status(res)}")
}
