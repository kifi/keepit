package com.keepit.normalizer

import com.keepit.common.net.{Query, Host, URI}
import com.keepit.common.net.Param

object LinkedInNormalizer extends StaticNormalizer {

  val linkedInPrivateProfile = """^https?://([a-z]{2,3})\.linkedin\.com/profile/view\?(?:.*&)?id=([0-9]{1,20}).*""".r
  val linkedInCanonicalPublicProfile = """^https?://([a-z]{2,3})\.linkedin\.com/(?:in/\w+(?:/[a-z]{2,3})?|pub/[\P{M}\p{M}\w]+(?:/\w+){3})(/)?$""".r
  val touchPublicProfile = """^https?://touch\.www\.linkedin\.com/.*#public-profile/https?://(www\.linkedin\.com)(/in/.*)""".r
  val touchRedirect = """^https?://touch\.www\.linkedin\.com/.*redirect_url=https?://(www\.linkedin\.com)(/.*)([?].*)""".r

  def isDefinedAt(uri: URI) = {
    (uri.host match {
      case Some(Host("com", "linkedin", _*)) => true
      case _ => false
    }) && (uri.raw match {
      case Some(linkedInPrivateProfile(country, id)) => true
      case Some(linkedInCanonicalPublicProfile(country, slash)) => true
      case Some(touchPublicProfile(linkedin, profile)) => true
      case Some(touchRedirect(linkedin, redirect, _)) => true
      case _ => false
    })
  }

  def apply(uri: URI) = {
    uri match {
      case URI(scheme, userInfo, host, port, path, query, _) => {
        uri.raw match {
          case Some(linkedInPrivateProfile(country, id)) => URI(scheme, userInfo, normalize(host), port, path, Some(Query(Seq(Param("id", Some(id))))), None)
          case Some(linkedInCanonicalPublicProfile(country, slash)) => URI(scheme, userInfo, normalize(host), port, path.map(p => if (slash != null) p.dropRight(1) else p), None, None)
          case Some(touchPublicProfile(linkedin, profile)) => URI(scheme, userInfo, Some(Host(linkedin)), port, Some(profile), None, None)
          case Some(touchRedirect(linkedin, redirect, _)) => URI(scheme, userInfo, Some(Host(linkedin)), port, Some(redirect), None, None)
          case _ => DefaultNormalizer(uri)
        }
      }
      case _ => DefaultNormalizer(uri)
    }
  }

  def normalize(host: Option[Host]) = {
    host match {
      case Some(Host("com", "linkedin")) | Some(Host("com", "linkedin", _)) => Some(Host("com", "linkedin", "www"))
      case _ => host
    }
  }
}
