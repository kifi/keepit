package com.keepit.rover.fetcher

import com.keepit.rover.fetcher.HttpRedirect._
import org.specs2.mutable.Specification
import play.api.http.Status.{ FOUND => TEMP_MOVED, MOVED_PERMANENTLY => MOVED }

class HttpRedirectTest extends Specification {
  "HttpRedirect" should {

    "Standardize relative redirects" in {
      withStandardizationEffort(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html") === HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html")
    }

    "Standardize relative redirects with crazy excaping" in {
      val redirect1 = withStandardizationEffort(MOVED,
        "http://dont.care.com",
        "http://www.travelnow.com/?additionalDataString=vrBookingSource%25252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525252525257ChotSearch&arrivalDay=5&arrivalMonth=4&cid=72255&city=New+York&country=Us&currencyCode=ILS&departureDay=6&departureMonth=4&generic=true&hotelID=134540&hotnetrates=true&landmarkDestinationID=aa469333-186f-2479-f572-4489780907ff&locale=en_IL&location=BETWEEN+2ND+AND+3RD+AVENUES&mode=2&numberOfRooms=1&pageName=hotAvail&postalCode=&propertyType=72&requestKey=120118D189AC11D42F012521045732&room-0-adult-total=2&showInfo=true&showPopUp=true&showPopUpMap=false&star=2&stateProvince=Ny&streetAddress=")
      val redirect2 = withStandardizationEffort(MOVED,
        "http://dont.care.com",
        "http://www.travelnow.com/?additionalDataString=vrBookingSource%25%7ChotSearch&arrivalDay=5&arrivalMonth=4&cid=72255&city=New+York&country=Us&currencyCode=ILS&departureDay=6&departureMonth=4&generic=true&hotelID=134540&hotnetrates=true&landmarkDestinationID=aa469333-186f-2479-f572-4489780907ff&locale=en_IL&location=BETWEEN+2ND+AND+3RD+AVENUES&mode=2&numberOfRooms=1&pageName=hotAvail&postalCode=&propertyType=72&requestKey=120118D189AC11D42F012521045732&room-0-adult-total=2&showInfo=true&showPopUp=true&showPopUpMap=false&star=2&stateProvince=Ny&streetAddress=")
      redirect1 === redirect2
    }

    "Find the correct permanent destination url after multiple redirects" in {
      resolve("http://www.lemonde.fr", Seq.empty) === None
      resolve("http://www.bbc.co.uk", Seq(HttpRedirect(MOVED, "http://www.bbc.co.uk", "http://www.bbc.com"))) === Some("http://www.bbc.com")

      val threePermanentRedirects = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )
      resolve("http://www.oracle.com/technology/index.html", threePermanentRedirects) === Some("http://www.oracle.com/technetwork/index.html")
      resolve("http://www.oracle.com", threePermanentRedirects) === None

      val withOneTemporaryRedirect = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(TEMP_MOVED, "https://www.oracle.com/technology/index.html", "https://www.oracle.com/technetwork/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )

      resolve("http://www.oracle.com/technology/index.html", withOneTemporaryRedirect) === Some("https://www.oracle.com/technology/index.html")

      val withOneRelativeRedirect = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html")
      )

      resolve("http://www.oracle.com/technology/index.html", withOneRelativeRedirect) === Some("https://www.oracle.com/technology/index.html")

      val withOneRecoverableRelativeRedirect = Seq(
        HttpRedirect(MOVED, "http://www.oracle.com/technology/index.html", "https://www.oracle.com/technology/index.html"),
        HttpRedirect(MOVED, "https://www.oracle.com/technology/index.html", "/technetwork/index.html"),
        HttpRedirect(MOVED, "/technetwork/index.html", "http://www.oracle.com/technetwork/index.html")
      )

      resolve("http://www.oracle.com/technology/index.html", withOneRecoverableRelativeRedirect) === Some("http://www.oracle.com/technetwork/index.html")

      val twoShortenersWithPermanentsRedirects = Seq(
        HttpRedirect(MOVED, "http://tcrn.ch/1FcO6rS", "http://bit.ly/1FcO6rS?cc=f5c993678dd8e5dc2f5bf1380c5de255"),
        HttpRedirect(MOVED, "http://bit.ly/1FcO6rS?cc=f5c993678dd8e5dc2f5bf1380c5de255", "http://techcrunch.com/2015/03/11/treeline-wants-to-take-the-coding-out-of-building-a-backend/")
      )

      resolve("http://tcrn.ch/1FcO6rS", twoShortenersWithPermanentsRedirects) === Some("http://techcrunch.com/2015/03/11/treeline-wants-to-take-the-coding-out-of-building-a-backend/")

      val twoShortenersWithOneTemporaryRedirect = Seq(
        HttpRedirect(TEMP_MOVED, "http://tcrn.ch/1FcO6rS", "http://bit.ly/1FcO6rS?cc=f5c993678dd8e5dc2f5bf1380c5de255"),
        HttpRedirect(MOVED, "http://bit.ly/1FcO6rS?cc=f5c993678dd8e5dc2f5bf1380c5de255", "http://techcrunch.com/2015/03/11/treeline-wants-to-take-the-coding-out-of-building-a-backend/")
      )

      resolve("http://tcrn.ch/1FcO6rS", twoShortenersWithPermanentsRedirects) === Some("http://techcrunch.com/2015/03/11/treeline-wants-to-take-the-coding-out-of-building-a-backend/")
    }
  }
}
