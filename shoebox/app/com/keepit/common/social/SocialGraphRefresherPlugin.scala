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
import akka.pattern.ask
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
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import scala.concurrent.Await

private case class RefreshUserInfo(socialUserInfo: SocialUserInfo)
private case object RefreshAll

private[social] class SocialGraphRefresherActor(socialGraphPlugin : SocialGraphPlugin) extends Actor with Logging {
  def receive() = {
    case RefreshAll => {
      log.info("going to check which SocilaUserInfo Was not fetched Lately")
      val needToBeRefreshed = CX.withConnection { implicit conn => SocialUserInfo.getNeedToBeRefreshed }
      log.info("find %s users that need to be refreshed".format(needToBeRefreshed.size))
      needToBeRefreshed.foreach(self ! RefreshUserInfo(_))
    }
    case RefreshUserInfo(userInfo) => {
      log.info("found socialUserInfo that need to be refreshed %s".format(userInfo))
      socialGraphPlugin.asyncFetch(userInfo)
    }
    case m => throw new Exception("unknown message %s".format(m))
  }
}


trait SocialGraphRefresher extends Plugin {

}

class SocialGraphRefresherImpl @Inject() (system: ActorSystem, socialGraphPlugin : SocialGraphPlugin) extends SocialGraphRefresher with Logging {
  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new SocialGraphRefresherActor(socialGraphPlugin) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 5 minutes, actor, RefreshAll)
    )
  }
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

}



