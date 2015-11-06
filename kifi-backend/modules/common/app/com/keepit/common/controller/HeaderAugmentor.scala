package com.keepit.common.controller

import com.keepit.common.net.URI
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
  def augment[A](result: Result)(implicit request: Request[A]): Result = {
    if (request.rawQueryString.contains("andrew-testing-security-headers")) {
      result.withHeaders(
        "Strict-Transport-Security" -> "max-age=16070400; includeSubDomains",
        "X-Frame-Options" -> "deny",
        "X-XSS-Protection" -> "1; mode=block",
        "X-Content-Type-Options" -> "nosniff",
      // REMOVE `unsafe-inline`s 
        "Content-Security-Policy-Report-Only" -> "default-src 'self' *.kifi.com d1dwdv9wd966qu.cloudfront.net djty7jcqog9qu.cloudfront.net fonts.googleapis.com ssl.google-analytics.com cdn.mxpnl.com d24n15hnbwhuhn.cloudfront.net; style-src unsafe-inline; script-src unsafe-inline" // report-uri https://www.kifi.com/up/report;
      )
    } else {
      result
    }
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
    val referrerOpt = request.headers.get("Referer").flatMap { ref =>
      URI.parse(ref).toOption.flatMap(_.host).map(_.name)
    }
    request.session.get("kcid").map { existingKcid =>
      if (existingKcid.startsWith("organic") && referrerOpt.exists(!_.contains("kifi.com"))) {
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
