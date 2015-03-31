package com.keepit.rover.article.content

import org.specs2.mutable.Specification
import play.api.libs.json.{ JsObject, Json }

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
}