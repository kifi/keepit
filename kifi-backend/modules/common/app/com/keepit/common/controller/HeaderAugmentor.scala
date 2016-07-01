package com.keepit.common.controller

import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.net.URI
import play.api.Mode
import play.api.mvc.{ Request, Result }
import play.api.http.Status.OK

object HeaderAugmentor {

  private val augmentors: Seq[Augmentor] = Vector(CORS, Kcid, Security)
  def process[A](result: Result)(implicit request: Request[A]): Result = {
    augmentors.foldLeft(result) {
      case (res, aug) =>
        aug.augment(res)
    }
  }

}

private sealed trait Augmentor {
  def augment[A](result: Result)(implicit request: Request[A]): Result
}

private object Security extends Augmentor {
  private val dev = if (play.api.Play.maybeApplication.exists(_.mode == Mode.Dev)) {
    "dev.ezkeep.com:9000 www.google-analytics.com d1scct5mnc9d9m.cloudfront.net"
  } else ""

  def augment[A](result: Result)(implicit request: Request[A]): Result = {
    val useTestHeaders = request.cookies.get("security-headers-test").isDefined || (request.id % 20 == 0)
    val useAlphaHeaders = request.cookies.get("security-headers-alpha").isDefined
    val cspIfNeeded = {
      if (useTestHeaders && result.header.headers.get("X-Nonce").isDefined && result.header.headers.get("Content-Type").exists(_.startsWith("text/html"))) {
        val nonce = result.header.headers.get("X-Nonce").get
        val cspStr =
          "" +
            s"style-src d1dwdv9wd966qu.cloudfront.net fonts.googleapis.com 'unsafe-inline'; " +
            s"script-src 'self' d1dwdv9wd966qu.cloudfront.net ssl.google-analytics.com d24n15hnbwhuhn.cloudfront.net cdn.mxpnl.com connect.facebook.net platform.twitter.com js.stripe.com checkout.stripe.com 'unsafe-eval' 'nonce-$nonce' 'sha256-XnNQECY9o+nIv2Qgcd1A39YarwxTm10rhdzegH/JBxY=' $dev; " + // sha is twitter's lib
            s"font-src fonts.gstatic.com data: ; " +
            s"img-src data: d1dwdv9wd966qu.cloudfront.net djty7jcqog9qu.cloudfront.net ssl.google-analytics.com stats.g.doubleclick.net static.xx.fbcdn.net q.stripe.com img.youtube.com www.kifi.com $dev; " +
            s"form-action www.kifi.com api.kifi.com $dev; " +
            s"frame-src www.kifi.com *.facebook.com *.stripe.com *.youtube.com; " + // to support Safari 9
            s"child-src www.kifi.com *.facebook.com *.stripe.com *.youtube.com; " +
            s"frame-ancestors 'self' $dev; " +
            s"connect-src www.kifi.com api.kifi.com search.kifi.com eliza.kifi.com api.mixpanel.com d1dwdv9wd966qu.cloudfront.net *.stripe.com api.airbrake.io $dev; " +
            "report-uri https://www.kifi.com/up/report"
        Seq("Content-Security-Policy-Report-Only" -> cspStr)
      } else if (useAlphaHeaders) {
        Seq()
      } else {
        Seq.empty
      }
    }

    val headers = Seq(
      "Strict-Transport-Security" -> "max-age=16070400; includeSubDomains",
      "X-XSS-Protection" -> "1; mode=block",
      "X-Content-Type-Options" -> "nosniff",
      "X-Frame-Options" -> "SAMEORIGIN"
    ) ++ cspIfNeeded

    result.withHeaders(headers: _*)
  }
}

private object CORS extends Augmentor {
  def augment[A](result: Result)(implicit request: Request[A]): Result = {
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    } map { h =>
      result.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      )
    } getOrElse result
  }
}

private object Kcid extends Augmentor {
  def augment[A](result: Result)(implicit request: Request[A]): Result = {
    request match {
      case ur: UserRequest[_] => result
      case _ =>
        if (result.header.status == OK && result.header.headers.get("Content-Type").exists(_.contains("text/html"))) {
          play.api.Play.maybeApplication.map(_ => augmentNonUser(result)).getOrElse(result)
        } else {
          result
        }
    }
  }

  private def augmentNonUser[A](result: Result)(implicit request: Request[A]): Result = {
    lazy val referrerOpt = request.headers.get("Referer").flatMap { ref =>
      URI.parse(ref).toOption.flatMap(_.host).map(_.name)
    }
    request.session.get("kcid").map { existingKcid =>
      if (existingKcid == "na-organic-na" && referrerOpt.exists(!_.contains("kifi.com"))) {
        result.removingFromSession("kcid").addingToSession("kcid" -> s"na-organic-${referrerOpt.getOrElse("na")}")(request)
      } else {
        result
      }
    }.getOrElse { // No KCID set
      request.queryString.get("kcid").flatMap(_.headOption).map { kcid =>
        result.addingToSession("kcid" -> kcid)(request)
      }.getOrElse {
        result.addingToSession("kcid" -> s"na-organic-${referrerOpt.getOrElse("na")}")(request)
      }
    }
  }
}
