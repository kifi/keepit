package com.keepit.controllers.website

import com.google.inject.Inject

import play.api.libs.json.Json

import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}

import scala.util.Random


//packet for easy testing. Likely not going to stay.
case class WTIDataPacket(name: String, invited: Boolean, invitedHowLongAgo: Option[Int], network: String, image: String, inNetworkId: String)
object WTIDataPacket {
  implicit val format = Json.format[WTIDataPacket]
}

class WTIController @Inject() (
  actionAuthenticator: ActionAuthenticator
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  private def gimmeSomething[T](seq: Seq[T]): T = {
    val rand = new Random(System.currentTimeMillis());
    seq(rand.nextInt(seq.length));
  }

  private def makeMeSomeData(howMuch: Int): List[WTIDataPacket] = {
    if (howMuch <= 0) {
      Nil
    } else {
      val rand = new Random(System.currentTimeMillis())
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

  def wti(page: Int) = JsonAction.authenticated { request =>
    Ok(Json.toJson(makeMeSomeData(20)))
  }
}


