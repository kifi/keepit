package com.keepit.common.net

import com.keepit.common.logging.Logging
import com.keepit.common.net.URIParserUtil._
import com.keepit.common.strings.UTF8
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

object URI extends Logging {

  def parse(uriString: String): Try[URI] = Try {
    val raw = uriString.trim
    if (raw.isEmpty) throw new Exception("empty uri string")
    val uri = URIParser.parseAll(URIParser.uri, raw).get

    apply(Option(raw), uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
  }

  def safelyParse(uriString: String): Option[URI] = parse(uriString) match {
    case Success(uri) => Some(uri)
    case Failure(e) =>
      log.error("uri parsing failed: [%s] caused by [%s]".format(uriString, e.getMessage))
      None
  }

  def apply(scheme: Option[String], userInfo: Option[String], host: Option[Host], port: Int, path: Option[String], query: Option[Query], fragment: Option[String]): URI =
    apply(None, scheme, userInfo, host, port, path, query, fragment)

  def apply(raw: Option[String], scheme: Option[String], userInfo: Option[String], host: Option[Host], port: Int, path: Option[String], query: Option[Query], fragment: Option[String]): URI =
    new URI(raw, scheme, userInfo, host, port, path, query, fragment)

  def unapply(uri: URI): Option[(Option[String], Option[String], Option[Host], Int, Option[String], Option[Query], Option[String])] =
    Some((uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment))

  def unapply(uriString: String): Option[(Option[String], Option[String], Option[Host], Int, Option[String], Option[Query], Option[String])] =
    unapplyTry(uriString).toOption

  def unapplyTry(uriString: String): Try[(Option[String], Option[String], Option[Host], Int, Option[String], Option[Query], Option[String])] = Try {
    val uri = URIParser.parseAll(URIParser.uri, uriString.trim).get
    (uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
  }

  /**
   * http://www.w3.org/TR/WD-html40-970917/htmlweb.html#h-5.1.2
   */
  def isRelative(uriString: String): Boolean = !uriString.contains("://")

  def isAbsolute(uriString : String): Boolean = !isRelative(uriString)

  def absoluteUrl(baseUri: URI, targetUrl: String): Option[String] =
    if (isRelative(targetUrl)) {
      val basePort = if (baseUri.port >= 0) s":${baseUri.port.toString}" else ""
      for {
        scheme <- baseUri.scheme
        host <- baseUri.host
      } yield {
        if (targetUrl.startsWith("#")) {
          new URI(None, baseUri.scheme, baseUri.userInfo, baseUri.host, baseUri.port, baseUri.path, baseUri.query, Some(targetUrl.substring(1))).toString()
        } else if (targetUrl.startsWith("/")) {
          s"$scheme://$host$basePort$targetUrl"
        } else if (targetUrl.startsWith("./")) {
          absoluteUrl(baseUri, targetUrl.substring(2)).get
        } else if (targetUrl.startsWith("../")) {
          val basePath = baseUri.path.map{p =>
            val base = if (p.endsWith("/")) p.substring(0, p.length - 1) else p
            base.substring(0, p.lastIndexOf("/"))
          }
          absoluteUrl(new URI(None, baseUri.scheme, baseUri.userInfo, baseUri.host, baseUri.port, basePath, None, None), targetUrl.substring(3)).get
        } else {
          val basePath = baseUri.path.map{path =>
            path.substring(0, path.lastIndexOf("/") + 1)
          } getOrElse "/"
          s"$scheme://$host$basePort$basePath$targetUrl"
        }
      }
    } else {
      Some(targetUrl)
    }

  def absoluteUrl(baseUrl: String, targetUrl: String): Option[String] =
    if (isAbsolute(targetUrl)) {
      Some(targetUrl)
    } else if (isAbsolute(baseUrl)) {
      for {
        baseUri <- safelyParse(baseUrl)
        absoluteTargetUrl <- absoluteUrl(baseUri, targetUrl)
      } yield absoluteTargetUrl
    } else {
      None
    }
}

class URI(val raw: Option[String], val scheme: Option[String], val userInfo: Option[String], val host: Option[Host], val port: Int, val path: Option[String], val query: Option[Query], val fragment: Option[String]) {
  override def toString() = {
    val updatedPath = (path, query) match {
      case (None, None) => None
      case (None, query @ Some(_)) => Some("/")
      case (Some("/"), None) => None
      case _ => path
    }

    val sb = new StringBuilder()

    scheme.foreach{ scheme => sb.append(scheme).append(":") }
    host.foreach{ host =>
      sb.append("//")
      userInfo.foreach{ userInfo => sb.append(userInfo).append("@") }
      sb.append(host)
      if (port >= 0) sb.append(":").append(port.toString)
    }
    updatedPath.foreach{ path => sb.append(path) }
    query.foreach{ query => if (query.params.size > 0) sb.append("?").append(query.toString) }
    fragment.foreach{ fragment => if (fragment.length > 0) sb.append("#").append(fragment) }

    sb.toString()
  }

  override def hashCode() = URI.unapply(this).hashCode()
  override def equals(o: Any) = o match {
    case that: URI if getClass == that.getClass => URI.unapply(this) == URI.unapply(that)
    case _ => false
  }
}

object Host {
  def parse(host: String) = URIParser.parseAll(URIParser.host, host.trim).get

  def apply(host: String*) = new Host(host.toSeq)

  def unapplySeq(host: Host): Option[Seq[String]] = Some(host.domain)
  def unapplySeq(host: String): Option[Seq[String]] = {
    val res = URIParser.parseAll(URIParser.host, host)
    if (res.successful) unapplySeq(res.get) else None
  }
}
class Host(val domain: Seq[String]) {
  def name: String = domain.reverse.mkString(".")
  override def toString = name
  override def hashCode() = domain.hashCode()
  override def equals(o: Any) = o match {
    case h: Host if getClass == h.getClass => domain == h.domain
    case _ => false
  }
}

object Query {
  def parse(query: String): Query = URIParser.parseAll(URIParser.query, query.trim).get

  def apply(params: Seq[Param]) = new Query(params)

  def unapplySeq(query: Query): Option[Seq[Param]] = Some(query.params)
  def unapplySeq(query: String): Option[Seq[Param]] = {
    val res = URIParser.parseAll(URIParser.query, query)
    if (res.successful) unapplySeq(res.get) else None
  }
}
class Query(val params: Seq[Param]) {
  override def toString() = {
    if (params.size > 0) params.mkString("&") else ""
  }

  override def hashCode() = params.hashCode()
  override def equals(o: Any) = o match {
    case that: Query if getClass == that.getClass => params == that.params
    case _ => false
  }
  def containsParam(name: String) = params.exists(_.name == name)
  def getParam(name: String) = params.find(_.name == name)
}

case class Param(name: String, value: Option[String]) {
  override def toString() = {
    if (value.isDefined) (name + "=" + value.get) else name
  }

  def isEmpty: Boolean = (name == "" && !value.isDefined)

  def decodedValue: Option[String] = value.map{ v => java.net.URLDecoder.decode(v, UTF8) }
}

