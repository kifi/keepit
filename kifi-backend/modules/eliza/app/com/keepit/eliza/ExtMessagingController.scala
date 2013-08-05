package com.keepit.eliza

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie

import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.iteratee.Concurrent
import play.api.libs.json.{Json, JsValue, JsArray}

import akka.actor.ActorSystem

import com.google.inject.Inject



class ExtMessagingController @Inject() (
    messagingController: MessagingController,
    actionAuthenticator: ActionAuthenticator,
    protected val shoebox: ShoeboxServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem
  ) 
  extends BrowserExtensionController(actionAuthenticator) with AuthenticatedWebSocketsController {



  /*********** REST *********************/

  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text, recipients) = (
      (o \ "url").as[String],
      (o \ "title").as[String],
      (o \ "text").as[String].trim,
      (o \ "recipients").as[Seq[String]])


    val responseFuture = messagingController.constructRecipientSet(recipients.map(ExternalId[User](_))).map{ recipientSet =>
      val message : Message = messagingController.sendNewMessage(request.user.id.get, recipientSet, Some(urlStr), text)
      Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))  
    }
    Async(responseFuture)
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val text = (o \ "text").as[String].trim

    val message = messagingController.sendMessage(request.user.id.get, threadExtId, text, None)

    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }


  /*********** WEBSOCKETS ******************/


  override protected def websocketHandlers(channel: Concurrent.Channel[JsArray]) = Map[String, Seq[JsValue] => Unit](
    "ping" -> { _ =>
      channel.push(Json.arr("pong"))
    }
  )




}



