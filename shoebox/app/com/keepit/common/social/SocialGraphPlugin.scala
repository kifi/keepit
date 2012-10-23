package com.keepit.common.social

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
import com.keepit.search.Article
import com.keepit.model.SocialUserInfo
import com.keepit.model.NormalizedURI
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import com.google.inject.Inject
import com.google.inject.Provider
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.model.User
import com.keepit.inject._
import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import play.api.Play.current
import play.api.libs.json.JsArray
import securesocial.core.{SocialUser, UserId, AuthenticationMethod, OAuth2Info}

//case object FetchAll
private case class FetchUserInfo(socialUserInfo: SocialUserInfo)

private[social] class SocialGraphActor(graph: FacebookSocialGraph) extends Actor with Logging {
  def receive() = {
//    case FetchAll => sender ! graph.fetchAll()
    case FetchUserInfo(user) => 
      val rawInfo = graph.fetchSocialUserRawInfo(user)
      log.info("fetched raw info %s for %s".format(rawInfo, user))
      CX.withConnection { implicit c =>
        user.withState(SocialUserInfo.States.FETCHED_USING_SELF).save
      }
      val store = inject[SocialUserRawInfoStore]
      store += (user.id.get -> rawInfo)
      
      val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD", 
        tokenType = None, expiresIn = None, refreshToken = None)
      
      (rawInfo.json \ "friends" \ "data").asInstanceOf[JsArray].value map { friend =>
        val socialId = SocialId((friend \ "username").asInstanceOf[String])
        CX.withConnection { implicit c =>
          SocialUserInfo.getOpt(socialId, SocialNetworks.FACEBOOK) match {
            case Some(socialUser) =>
              // do nothing for now. later we will merge with current record
            case None =>
              val socialUserInfo = SocialUser(UserId("100004067535411", "facebook"), "Boaz Tal", Some("boaz.tal@gmail.com"), 
                Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          }
        }
      }
      
      /*.asInstanceOf[JsArray] {
        val socialId = ...
        SocialUserInfo.getOpt(socialId, NetworkTypes.FACEBOOK) match {
          
        }
      }*/
      /*
       * for each son in the user friends list
       *   if (facebook id already has a local social user info) -> ignore //in the future we'll do a merge 
       *   create new SocialUserInfo and persist
       *   create new SocialUserRawInfo with the json snippet 
       *   push the SocialUserRawInfo to s3
       *   mark the SocialUserRawInfo as fetched_using_friend
       */
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait SocialGraphPlugin extends Plugin {
//  def scrape(): Seq[(NormalizedURI, Option[Article])]
  def asyncFetch(socialUserInfo: SocialUserInfo): Unit
}

class SocialGraphPluginImpl @Inject() (system: ActorSystem, socialGraph: FacebookSocialGraph) extends SocialGraphPlugin with Logging {
  
  implicit val actorTimeout = Timeout(5 seconds)
  
  private val actor = system.actorOf(Props { new SocialGraphActor(socialGraph) })
  
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting SocialGraphPluginImpl")
//    _cancellables = Seq(
//      system.scheduler.schedule(0 seconds, 1 minutes, actor, FetchAll)
//    )
  }
  override def onStop(): Unit = {
    log.info("stopping SocialGraphPluginImpl")
    _cancellables.map(_.cancel)
  }
  
//  override def fetchAll(): Seq[(NormalizedURI, Option[Article])] = {
//    val future = actor.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]
//    Await.result(future, 1 minutes)
//  }
  
  override def asyncFetch(socialUserInfo: SocialUserInfo): Unit = actor ! FetchUserInfo(socialUserInfo)
}



