package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.logging.Logging
import com.keepit.common.net.UserAgent
import com.keepit.common.service.IpAddress
import com.keepit.common.time.Clock
import com.keepit.controllers.core.{AuthController, AuthHelper}
import com.keepit.heimdal.{ContextDoubleData, HeimdalContext, HeimdalContextBuilderFactory, HeimdalServiceClient, UserEvent, UserEventTypes}
import com.keepit.model._
import com.keepit.social.providers.ProviderController
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsNumber, JsValue, Json}
import play.api.mvc.{Cookie, Result, Session}
import securesocial.core.{OAuth2Info, SecureSocial}

import scala.concurrent.Future

class MobileAuthController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    clock: Clock,
    socialUserInfoRepo: SocialUserInfoRepo,
    installationRepo: KifiInstallationRepo,
    authHelper: AuthHelper,
    contextBuilderFactory: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient) extends UserActions with ShoeboxServiceController with Logging {

  private implicit val readsOAuth2Info = Json.reads[OAuth2Info]

  def whatIsMyIp() = MaybeUserAction { request =>
    val ip = IpAddress.fromRequest(request).ip
    Ok(ip)
  }

  def registerIPhoneVersion() = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val installationIdOpt = (json \ "installation").asOpt[String].map(ExternalId[KifiInstallation](_))
    val version = KifiIPhoneVersion((json \ "version").as[String])
    val agent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val context = contextBuilderFactory.withRequestInfo(request).build
    registerMobileVersion(installationIdOpt, version, agent, request.userId, KifiInstallationPlatform.IPhone, context)
  }

  def registerAndroidVersion() = UserAction(parse.tolerantJson) { request =>
    val json = request.body
    val installationIdOpt = (json \ "installation").asOpt[String].map(ExternalId[KifiInstallation](_))
    val version = KifiAndroidVersion((json \ "version").as[String])
    val agent = UserAgent(request.headers.get("user-agent").getOrElse(""))
    val context = contextBuilderFactory.withRequestInfo(request).build
    registerMobileVersion(installationIdOpt, version, agent, request.userId, KifiInstallationPlatform.Android, context)
  }

  private def registerMobileVersion[T <: KifiVersion with Ordered[T]](installationIdOpt: Option[ExternalId[KifiInstallation]], version: T, agent: UserAgent, userId: Id[User], platform: KifiInstallationPlatform, context: HeimdalContext) = {

    val (installation, newInstallation) = installationIdOpt map { id =>
      db.readOnlyMaster { implicit s => installationRepo.get(id) }
    } match {
      case None =>
        db.readWrite { implicit s =>
          log.info(s"installation for user $userId does not exist, creating a new one")
          (installationRepo.save(KifiInstallation(userId = userId, userAgent = agent, version = version, platform = platform)), true)
        }
      case Some(existing) if !existing.isActive =>
        db.readWrite { implicit s =>
          log.info(s"activating installation $existing with latest version")
          (installationRepo.save(existing.withState(KifiInstallationStates.ACTIVE).withVersion(version)), false)
        }
      case Some(existing) if existing.userId != userId =>
        db.readWrite { implicit s =>
          log.info(s"installation $existing is not of user $userId, creating a new installation for user")
          (installationRepo.save(KifiInstallation(userId = userId, userAgent = agent, version = version, platform = platform)), true)
        }
      case Some(active) if active.version.asInstanceOf[T] < version =>
        db.readWrite { implicit s =>
          log.info(s"installation ${active.externalId} for user $userId exist but outdated, updating")
          (installationRepo.save(active.withVersion(version)), false)
        }
      case Some(active) if active.version.asInstanceOf[T] > version =>
        val message = s"TIME TRAVEL!!! installation ${active.externalId} for user $userId has version ${active.version} while we got from client an older version $version"
        db.readWrite { implicit s =>
          log.warn(message)
          (installationRepo.save(active.withVersion(version)), false)
        }
      case Some(active) =>
        log.info(s"installation ${active.externalId} for user $userId exist and version match")
        (active, false)
    }

    reportMobileInstallation(userId, installation, newInstallation, platform, context)

    Ok(Json.obj(
      "installation" -> installation.externalId.toString,
      "newInstallation" -> newInstallation
    ))
  }

  private def reportMobileInstallation(userId: Id[User], installation: KifiInstallation, isNewInstall: Boolean, platform: KifiInstallationPlatform, context: HeimdalContext): Unit = {
    val builder = contextBuilderFactory()
    builder.addExistingContext(context)
    builder += ("extensionVersion", installation.version.toString)
    builder += ("kifiInstallationId", installation.id.get.toString)
    builder += ("device", platform.name)
    if (isNewInstall) {
      builder += ("action", "installedExtension")
      val numInstallations = db.readOnlyReplica { implicit session => installationRepo.all(userId, Some(KifiInstallationStates.INACTIVE)).length } // all platforms
      builder += ("installation", numInstallations)
      heimdal.setUserProperties(userId, "installedExtensions" -> ContextDoubleData(numInstallations))
      heimdal.trackEvent(UserEvent(userId, builder.build, UserEventTypes.JOINED))
    } else {
      heimdal.trackEvent(UserEvent(userId, builder.build, UserEventTypes.UPDATED_EXTENSION))
    }
  }

  def accessTokenSignup(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenSignup(providerName)
  }

  def accessTokenLogin(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenLogin(providerName)
  }

  def oauth1TokenSignup(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenSignup(providerName)
  }

  def oauth1TokenLogin(providerName: String) = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doAccessTokenLogin(providerName)
  }

  def socialFinalizeAccountAction() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.doSocialFinalizeAccountAction(request)
  }

  def loginWithUserPass(link: String) = MaybeUserAction.async(parse.anyContent) { implicit request =>
    ProviderController.authenticate("userpass")(request).map {
      case res: Result if res.header.status == 303 =>
        authHelper.transformResult(res) { (cookies: Seq[Cookie], sess: Session) =>
          val newSession = if (link != "") {
            sess - SecureSocial.OriginalUrlKey + (AuthController.LinkWithKey -> link) // removal of OriginalUrlKey might be redundant
          } else sess
          Ok(Json.obj("code" -> "auth_success")).withCookies(cookies: _*).withSession(newSession)
        }
      case res => res
    }
  }

  def userPasswordSignup() = MaybeUserAction(parse.tolerantJson) { implicit request =>
    authHelper.userPasswordSignupAction(request)
  }

  def userPassFinalizeAccountAction() = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    request match {
      case ur: UserRequest[JsValue] => authHelper.doUserPassFinalizeAccountAction(ur)
      case _ => resolve(Forbidden(JsNumber(0)))
    }
  }

  def uploadBinaryPicture() = MaybeUserAction(parse.temporaryFile) { implicit request =>
    authHelper.doUploadBinaryPicture
  }

  def uploadFormEncodedPicture() = MaybeUserAction(parse.multipartFormData) { implicit request =>
    authHelper.doUploadFormEncodedPicture
  }

  // this one sends an email with a link to a page -- more work for mobile likely needed
  def forgotPassword() = MaybeUserAction.async(parse.tolerantJson) { implicit request =>
    authHelper.doForgotPassword
  }
}
