package com.keepit.controllers.website

import com.google.inject.Inject

import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.abook.ABookServiceClient
import com.keepit.model.SocialUserInfoRepo
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.User

import scala.util.Random
import com.keepit.commanders.{InviteCommander, ShoeboxRichConnectionCommander, FullSocialId}
import scala.concurrent.Future


//packet for easy testing. Likely not going to stay.
case class WTIDataPacket(name: String, invited: Boolean, invitedHowLongAgo: Option[Int], network: String, image: String, inNetworkId: String)
object WTIDataPacket {
  implicit val format = Json.format[WTIDataPacket]
}

class WTIController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander,
  inviteCommander: InviteCommander,
  abook: ABookServiceClient,
  socialUserInfoRepo: SocialUserInfoRepo,
  db: Database
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  private val rand = new Random(System.currentTimeMillis())

  private def gimmeSomething[T](seq: Seq[T]): T = {
    seq(rand.nextInt(seq.length));
  }

  private def makeMeSomeData(howMuch: Int): List[WTIDataPacket] = {
    if (howMuch <= 0) {
      Nil
    } else {
      WTIDataPacket(
        name = gimmeSomething(Seq("Stephen", "Andrew", "Léo", "Eishay", "Danny", "Jared")) + " " +  gimmeSomething(Seq("Kemmerling", "Conner", "Grimaldi", "Smith", "Blumenfeld", "Jacobs")) + " " + rand.nextInt(500),
        invited = gimmeSomething(Seq(false, false, false, true)),
        invitedHowLongAgo = Some(rand.nextInt(500)),
        network = gimmeSomething(Seq("facebook", "linkedin")),
        image = "http://lorempixel.com/64/64/people",
        inNetworkId = rand.nextLong.toString
      ) :: makeMeSomeData(howMuch - 1)
    }
  }

  private def makeMeSomeRealData(user: Id[User], howMuch: Int): Future[Seq[WTIDataPacket]]= {
    abook.ripestFruit(user, 20).map{ socialIds =>
      val socialUsers = db.readOnly { implicit session => socialIds.map(socialUserInfoRepo.get(_)) }
      socialUsers.map { socialUser =>
        WTIDataPacket(
          name = socialUser.fullName,
          invited = gimmeSomething(Seq(false, false, false, true)),
          invitedHowLongAgo = Some(rand.nextInt(500)),
          network = socialUser.networkType.name,
          image = socialUser.getPictureUrl(64,64).getOrElse(""),
          inNetworkId = socialUser.socialId.id
        )
      }
    }
  }

  def wti(page: Int) = JsonAction.authenticatedAsync { request =>
    makeMeSomeRealData(request.userId, 20).map(wtiPackets => Ok(Json.toJson(wtiPackets)))
  }

  def block() = JsonAction.authenticated(parse.json) { request =>
    (request.body \ "fullSocialId").asOpt[FullSocialId] match {
      case None => BadRequest("0")
      case Some(fullSocialId) => {
        shoeboxRichConnectionCommander.block(request.userId, fullSocialId)
        Ok
      }
    }
  }

  def getRipestInvitees(page: Int, pageSize: Int) = JsonAction.authenticatedAsync { request =>
    inviteCommander.getRipestInvitees(request.userId, page, pageSize).map { ripestInvitees =>
      Ok(Json.toJson(ripestInvitees))
    }
  }
}


