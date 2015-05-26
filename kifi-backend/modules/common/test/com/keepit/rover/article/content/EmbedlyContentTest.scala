package com.keepit.rover.article.content

import org.specs2.mutable.Specification
import play.api.libs.json.{ JsString, JsObject, Json }

class EmbedlyContentTest extends Specification {

  val embedlyJson = Json.parse(EmbedlyTestResponse.rawString).as[JsObject]

  "EmbedlyContent" should {
    "parse Embedly response" in {
      new EmbedlyContent(embedlyJson)
      "Well done!" === "Well done!"
    }

    "parse Embedly response without a media field" in {
      new EmbedlyContent(embedlyJson - "media")
      "Well done!" === "Well done!"
    }

    "parse Embedly response with an empty object media field" in {
      new EmbedlyContent(embedlyJson + ("media" -> Json.obj()))
      "Well done!" === "Well done!"
    }

    "extract text from html content" in {
      val embedlyContent = new EmbedlyContent(embedlyJson + ("content" -> JsString(EmbedlyTestResponse.rawHTMLContent)))
      embedlyContent.content.get === EmbedlyTestResponse.cleanHTMLContent
      "Well done!" === "Well done!"
    }
  }
}

object EmbedlyTestResponse {
  val rawString = """
    |{
      |"provider_url": "http://thedailyshow.cc.com/",
      |"description": "Donald Trump takes Sarah Palin to a pizza chain and eats his stacked slices with a fork. Air Date: June 1, 2011",
      |"embeds": [],
      |"safe": true,
      |"provider_display": "thedailyshow.cc.com",
      |"related": [],
      |"favicon_url": "http://thedailyshow.cc.com/favicon.ico",
      |"authors": [],
      |"images": [{
        |"caption": null, "url": "http://thedailyshow.mtvnimages.com/images/shows/tds/videos/season_16/16070/ds_16070_02_16x9.jpg?crop=true",
        |"height": 1080,
        |"width": 1920,
        |"colors": [
          |{"color": [21, 16, 28], "weight": 0.57421875},
          |{"color": [0, 28, 90], "weight": 0.23291015625},
          |{"color": [85, 75, 76], "weight": 0.026611328125},
          |{"color": [180, 27, 12], "weight": 0.01953125},
          |{"color": [111, 57, 40], "weight": 0.019287109375}
        |],
        |"entropy": 6.29620306085,
        |"size": 272381
      |}],
      |"cache_age": 86181,
      |"language": "Portuguese",
      |"app_links": [],
      |"original_url": "http://thedailyshow.cc.com/videos/0ect4f/me-lover-s-pizza-with-crazy-broad",
      |"url": "http://thedailyshow.cc.com/videos/0ect4f/me-lover-s-pizza-with-crazy-broad",
      |"media": {
        |"width": 640,
        |"html": "<iframe class=\"embedly-embed\" src=\"//cdn.embedly.com/widgets/media.html?src=http%3A%2F%2Fmedia.mtvnservices.com%2Ffb%2Fmgid%3Aarc%3Avideo%3Athedailyshow.com%3A3ed04efe-ed01-11e0-aca6-0026b9414f30.swf&url=http%3A%2F%2Fthedailyshow.cc.com%2Fvideos%2F0ect4f%2Fme-lover-s-pizza-with-crazy-broad&image=http%3A%2F%2Fthedailyshow.mtvnimages.com%2Fimages%2Fshows%2Ftds%2Fvideos%2Fseason_16%2F16070%2Fds_16070_02_16x9.jpg%3Fcrop%3Dtrue&key=52052e50a0f442b1bb6d59742d0fde22&type=application%2Fx-shockwave-flash&schema=cc\" width=\"640\" height=\"360\" scrolling=\"no\" frameborder=\"0\" allowfullscreen></iframe>",
        |"type": "video",
        |"height": 360
      |},
      |"title": "Me Lover's Pizza with Crazy Broad",
      |"offset": null,
      |"lead": null,
      |"content": null,
      |"entities": [],
      |"favicon_colors": [{"color": [41, 122, 169],"weight": 0.000244140625}, {"color": [252, 252, 252], "weight": 0.000244140625}],
      |"keywords": [],
      |"published": null,
      |"provider_name": "The Daily Show",
      |"type": "html"
    |}
  """.stripMargin

  val rawHTMLContent = """<div> <br><a href='http://thedailyshow.cc.com/faq#faq1'>What's new about this redesigned website?</a><br><a href='http://thedailyshow.cc.com/faq#faq2'>How many full episodes can I watch on my computer?</a><br><a href='http://thedailyshow.cc.com/faq#faq3'>How many full episodes can I watch on my phone and/or tablet?</a><br><a href='http://thedailyshow.cc.com/faq#faq4'>I'm having trouble watching videos. How can I fix this?</a><br><a href='http://thedailyshow.cc.com/faq#faq5'>Which browsers and platforms will give me the best experience on this website?</a><br><a href='http://thedailyshow.cc.com/faq#faq6'>Can I watch The Daily Show on this website outside the United States?</a><br><a href='http://thedailyshow.cc.com/faq#faq7'>I want to attend a taping of The Daily Show. How do I reserve tickets?</a><br><a href='http://thedailyshow.cc.com/faq#faq8'>I want to be considered for an internship at The Daily Show. How do I apply?</a> <p><b>What's new about this redesigned website?</b><br>We've made three key improvements:<br></p><ol><li>Now you can watch The Daily Show on your phone and tablet as well as your computer. <a href='http://thedailyshow.cc.com/full-episodes'>Full episodes</a>, <a href='http://thedailyshow.cc.com/videos'>videos</a>, <a href='http://thedailyshow.cc.com/extended-interviews'>extended interviews</a> and <a href='http://thedailyshow.cc.com/guests'>guest updates</a> are available whenever you want, wherever you are.</li> <li>The <a href='http://thedailyshow.cc.com/'>homepage</a> is much more visual, making it easier for you to find the latest full episode, new extended interviews or a video from The Daily Show archive that you didn't even know you wanted to watch.</li> <li>Our video player is bigger. Beneath the player, you'll find more clips related to the one you just watched, plus an easy-to-browse list of the videos that everyone else is watching right now.</li> </ol><b>How many full episodes can I watch on my computer?</b><br>You can watch the 16 most recent episodes on your computer. <p><b>How many full episodes can I watch on my phone and/or tablet?</b><br>You can watch the four most recent episodes on your phone and/or tablet.</p> <p><b>I'm having trouble watching videos. How can I fix this?</b><br>You can:<br></p><ol><li>Update your current browser to the latest version for best performance. </li> <li>If you're using a phone or tablet, make sure that you are using a strong wireless network or a strong signal from your mobile service provider.</li> </ol><b> Which browsers and platforms will give me the best experience on this website?</b><br>For mobile users, this website is optimized for iPhones, iPads and iPod Touch devices that use iOS7 or higher and Android devoices that use Android 4.3 or higher. For desktop users, we recommend using the latest versions of Firefox, Chrome, Safari or Internet Explorer. <p><b> Can I watch The Daily Show on this website outside the United States?</b><br>Sorry, no. But Comedy Central's partners offer The Daily Show in several countries -- watch videos and check television listings here:</p><ol><li><a href='http://www.thecomedynetwork.ca/shows/thedailyshow'>The Daily Show in Canada</a></li> <li> <a href='http://www.comedycentral.co.uk/shows/featured/the-daily-show'>The Daily Show in the United Kingdom</a> </li> <li><a href='http://www.thecomedychannel.com.au/shows/the-daily-show-with-jon-stewart'>The Daily Show in Australia</a></li> <li><a href='http://www.comedycentralafrica.com'>The Daily Show in South Africa</a></li> </ol>You can also download full episodes. Get <a href='https://itunes.apple.com/us/tv-season/daily-show-jon-stewart/id129455338'>The Daily Show on iTunes</a> or get <a href='http://www.amazon.com/gp/product/B00HQQOQ74?ref_=atv_dp_season_select_s19'>The Daily Show on Amazon</a>. <p><b>I want to attend a taping of The Daily Show. How do I reserve tickets?</b><br>Fill out our reservation form to get <a href='http://thedailyshow.cc.com/tickets'>tickets to The Daily Show</a>, subject to availability. Tickets are free, and you can get up to four per reservation. For last-minute ticket updates, follow <a href='https://twitter.com/thedailyshow'>@TheDailyShow</a> on Twitter.</p> <p><b>I want to be considered for an internship at The Daily Show. How do I apply?</b><br>Prospective interns should email <a href='mailto:internship@thedailyshow.com'>internship@thedailyshow.com</a> to receive more information about the application process. No other messages sent to this email address will be read or responded to.</p> </div>"""
  val cleanHTMLContent = """What's new about this redesigned website? How many full episodes can I watch on my computer? How many full episodes can I watch on my phone and/or tablet? I'm having trouble watching videos. How can I fix this? Which browsers and platforms will give me the best experience on this website? Can I watch The Daily Show on this website outside the United States? I want to attend a taping of The Daily Show. How do I reserve tickets? I want to be considered for an internship at The Daily Show. How do I apply? What's new about this redesigned website? We've made three key improvements: Now you can watch The Daily Show on your phone and tablet as well as your computer. Full episodes, videos, extended interviews and guest updates are available whenever you want, wherever you are. The homepage is much more visual, making it easier for you to find the latest full episode, new extended interviews or a video from The Daily Show archive that you didn't even know you wanted to watch. Our video player is bigger. Beneath the player, you'll find more clips related to the one you just watched, plus an easy-to-browse list of the videos that everyone else is watching right now. How many full episodes can I watch on my computer? You can watch the 16 most recent episodes on your computer. How many full episodes can I watch on my phone and/or tablet? You can watch the four most recent episodes on your phone and/or tablet. I'm having trouble watching videos. How can I fix this? You can: Update your current browser to the latest version for best performance. If you're using a phone or tablet, make sure that you are using a strong wireless network or a strong signal from your mobile service provider. Which browsers and platforms will give me the best experience on this website? For mobile users, this website is optimized for iPhones, iPads and iPod Touch devices that use iOS7 or higher and Android devoices that use Android 4.3 or higher. For desktop users, we recommend using the latest versions of Firefox, Chrome, Safari or Internet Explorer. Can I watch The Daily Show on this website outside the United States? Sorry, no. But Comedy Central's partners offer The Daily Show in several countries -- watch videos and check television listings here: The Daily Show in Canada The Daily Show in the United Kingdom The Daily Show in Australia The Daily Show in South Africa You can also download full episodes. Get The Daily Show on iTunes or get The Daily Show on Amazon. I want to attend a taping of The Daily Show. How do I reserve tickets? Fill out our reservation form to get tickets to The Daily Show, subject to availability. Tickets are free, and you can get up to four per reservation. For last-minute ticket updates, follow @TheDailyShow on Twitter. I want to be considered for an internship at The Daily Show. How do I apply? Prospective interns should email internship@thedailyshow.com to receive more information about the application process. No other messages sent to this email address will be read or responded to."""
}