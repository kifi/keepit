package com.keepit.controllers

import java.net.URLEncoder
import java.util.GregorianCalendar
import java.util.HashMap
import play.api.mvc.{ Action, Controller }
import play.api.mvc.BodyParsers.parse.tolerantJson
import play.api.i18n.Messages
import play.api.{ Play, Logger }
import Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.model._
import com.keepit.common.db.CX
import play.api.libs.ws.WS
import org.jivesoftware.smack._
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.sasl.SASLMechanism
import org.jivesoftware.smack.util.Base64
import javax.security.sasl.Sasl
import com.keepit.common.logging.Logging
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Message.Type
import com.keepit.common.db.Id
import play.api.libs.json.JsValue
import securesocial.core._
import securesocial.core.providers.FacebookProvider
import com.keepit.common.db.ExternalId
import securesocial.core.providers.FacebookProvider
import com.keepit.common.social.SocialId
import com.keepit.common.controller.FortyTwoController

object ChatController extends FortyTwoController {

  def createConnection() = {
    val config = new ConnectionConfiguration("chat.facebook.com", 5222)
    //    Connection.DEBUG_ENABLED = true;
    //    config.setDebuggerEnabled(Connection.DEBUG_ENABLED);
    config.setSASLAuthenticationEnabled(true)
    SmackConfiguration.setPacketReplyTimeout(15000)
    val connection = new XMPPConnection(config)
    SASLAuthentication.registerSASLMechanism("X-FACEBOOK-PLATFORM", classOf[SASLXFacebookPlatformMechanism])
    SASLAuthentication.supportSASLMechanism("X-FACEBOOK-PLATFORM", 0)
    connection
  }

  private def parseMessage(value: JsValue): String = (value \ "message").as[String]
  private def parseUrl(value: JsValue): String = (value \ "url").as[String]

  def chat(receipantExternalId: ExternalId[User]) = SecuredAction(false, tolerantJson) { implicit request =>
    val url = parseUrl(request.body)
    val message = parseMessage(request.body)
    log.info("will chat with user (externalId) %s, url is %s and message is %s".format(receipantExternalId, url, message))

    val receipantSocialUserInfo = CX.withConnection { implicit c =>
      val user = UserCxRepo.get(receipantExternalId)
      val infos = SocialUserInfoCxRepo.getByUser(user.id.get)
      //at this point we must have a single info per user
      if (infos.size != 1) throw new Exception("info for %s is not ONE: \n%s".format(user, infos.mkString("\n")))
      infos(0)
    }

    val settings = ProviderRegistry.get(FacebookProvider.Facebook).get.asInstanceOf[OAuth2Provider].settings

    val connection = createConnection()
    connection.connect()
    log.info("chatting using %s".format(receipantSocialUserInfo))
    val accessToken = request.user.oAuth2Info.map(info => info.accessToken).getOrElse(throw new IllegalStateException("access token is missing for user %s".format(receipantSocialUserInfo)))
    val apiKey = settings.clientId
    connection.login(apiKey, accessToken)
    send(connection, url, message, receipantSocialUserInfo.socialId)
    connection.disconnect()

    Ok(JsObject(("status" -> JsString("success")) :: Nil))
  }

  private def send(connection: XMPPConnection, url: String, txt: String, receipant: SocialId) {
    println("sending msg to %s about URL %s (%s)".format(receipant, url, txt))
    connection.getChatManager().createChat("-%s@chat.facebook.com".format(receipant), new MessageListener() {
      def processMessage(chat: Chat, message: Message) =
        println("Received message from %s: %s ".format(message.getFrom, message.getBody))
    }).sendMessage("looking now at %s. and wanted to tell you: %s".format(url, txt))
  }
}

object SASLXFacebookPlatformMechanism {
  val NAME = "X-FACEBOOK-PLATFORM"
}

class SASLXFacebookPlatformMechanism(saslAuthentication: SASLAuthentication) extends SASLMechanism(saslAuthentication) {
  var apiKey = ""
  var accessToken = ""

  override def authenticate {
    getSASLAuthentication().send(new AuthMechanism(getName(), ""))
  }

  override def authenticate(apiKey: String, host: String, accessToken: String) {
    this.apiKey = apiKey
    this.accessToken = accessToken
    this.hostname = host

    val mechanisms = Array("DIGEST-MD5")
    val props = new HashMap[String, String]()
    this.sc = Sasl.createSaslClient(mechanisms, null, "xmpp", "chat.facebook.com", props, this)
    authenticate
  }

  override def getName = SASLXFacebookPlatformMechanism.NAME

  override def challengeReceived(challenge: String) {
    val decodedChallenge = new String(Base64.decode(challenge))
    val parameters = getQueryMap(decodedChallenge);
    val version = "1.0"
    val nonce = parameters("nonce")
    val method = parameters("method")

    val callId = new GregorianCalendar().getTimeInMillis() / 1000L;

    val composedResponse = "api_key=" + URLEncoder.encode(apiKey, "utf-8") +
      "&call_id=" + callId +
      "&method=" + URLEncoder.encode(method, "utf-8") +
      "&nonce=" + URLEncoder.encode(nonce, "utf-8") +
      "&access_token=" + URLEncoder.encode(accessToken, "utf-8") +
      "&v=" + URLEncoder.encode(version, "utf-8");

    val response = composedResponse.getBytes("utf-8");
    val authenticationText = new String(Base64.encodeBytes(response, Base64.DONT_BREAK_LINES))

    getSASLAuthentication().send(new Response(authenticationText))
  }

  def getQueryMap(query: String) = Map(query.split("\\&").map(pair => pair.split("=", 2)).filter(pairs => pairs.length == 2).map(fields => fields(0) -> fields(1)): _*)

}

