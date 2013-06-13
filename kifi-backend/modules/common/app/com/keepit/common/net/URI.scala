package com.keepit.common.net

import scala.util.Try

import com.keepit.common.logging.Logging
import com.keepit.common.strings.UTF8

object URI extends Logging {
  def parse(uriString: String): Try[URI] = {
    unapplyTry(uriString).map { case (scheme, userInfo, host, port, path, query, fragment) =>
      URI(Some(uriString), scheme, userInfo, host, port, path, query, fragment)
    }
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
    val uri = try {
      new java.net.URI(uriString).normalize()
    } catch {
      case e: java.net.URISyntaxException =>
        val fixedUriString = encodeExtraDelimiters(encodeSymbols(fixMalformedEscape(uriString)))
        new java.net.URI(fixedUriString).normalize()
    }
    val scheme = normalizeScheme(Option(uri.getScheme))
    val (userInfo, host, port) = normalizeAuthority(
      scheme, Option(uri.getAuthority), Option(uri.getUserInfo), Option(uri.getHost), uri.getPort)
    val path = normalizePath(Option(uri.getPath))
    val query = normalizeQuery(Option(uri.getRawQuery))
    val fragment = normalizeFragment(Option(uri.getRawFragment))
    (scheme, userInfo, host, port, path, query, fragment)
  }

  val authorityRe = """(?:([^@]*)@)?(.*?)(?::(\d{1,5}))?""".r
  val twoHexDigits = """\p{XDigit}\p{XDigit}""".r
  val encodedPercent = java.net.URLEncoder.encode("%", UTF8)
  val symbols = """'"`@$^()[]{}<>\| """  // note: may want to remove '@$() as java.net.URI doesn't require escaping them
  val symbolRe = ("[\\Q" + symbols + "\\E]").r
  val delimiters = "?#"
  val encodingMap: Map[Char, String] = (symbols ++ delimiters).map(c => c -> java.net.URLEncoder.encode(c.toString, UTF8)).toMap

  def fixMalformedEscape(uriString: String) = {
    uriString.split("%", -1) match {
      case Array(first, rest @ _*) =>
        rest.foldLeft(first) { (str, piece) =>
          str + twoHexDigits.findPrefixOf(piece).map(_ => "%").getOrElse(encodedPercent) + piece
        }
    }
  }

  def encodeExtraDelimiters(uriString: String): String = {
    var s = uriString
    delimiters.foreach { c =>
      val (i, j) = (s.indexOf(c), s.lastIndexOf(c))
      if (j > i) {
        s = s.substring(0, i + 1) + s.substring(i + 1).replace(c.toString, encodingMap(c))
      }
    }
    s
  }

  def encodeSymbols(uriString: String): String = symbolRe.replaceAllIn(uriString, m => encodingMap(m.group(0)(0)))

  def normalizeScheme(scheme: Option[String]): Option[String] = scheme.map(_.toLowerCase)

  def normalizeAuthority(scheme: Option[String], authority: Option[String],
      userInfo: Option[String], host: Option[String], port: Int)
      : (Option[String], Option[Host], Int) = {
    if (host.isDefined) {
      (userInfo, Some(normalizeHost(host.get)), normalizePort(scheme, port))
    } else {
      authority match {
        case Some(authorityRe(u, h, p)) =>
          (Option(u), Some(normalizeHost(h)), normalizePort(scheme, Option(p).map(_.toInt).getOrElse(-1)))
        case Some(a) =>
          log.error(s"authority normalization failed: [$a]")
          (None, Some(normalizeHost(a)), -1)
        case None =>
          (None, None, -1)
      }
    }
  }

  def normalizeHost(host: String): Host = host match {
    case Host(domain @ _*) =>
      Host(domain: _*)
    case host =>
      log.error("host normalization failed: [%s]".format(host))
      Host(host)
  }

  def normalizePort(scheme: Option[String], port: Int): Int = (scheme, port) match {
    case (Some("http"), 80) => -1
    case (Some("https"), 443) => -1
    case _ => port
  }

  val slashDotDot = """^(/\.\.)+""".r

  val defaultPage = """/(index|default)\.(html|htm|asp|aspx|php|php3|php4|phtml|cfm|cgi|jsp|jsf|jspx|jspa)$""".r

  def normalizePath(path: Option[String]) = {
    path.flatMap{ path =>
      var path2 = slashDotDot.replaceFirstIn(path.trim, "")
      defaultPage.findFirstMatchIn(path2.toLowerCase).foreach{ m =>
        val delta = path2.length - path2.toLowerCase.length // in case the case conversion changed the length
        path2 = path2.substring(0, m.start + delta) + "/"
      }
      path2 match {
        case "" => None
        case path => Some(path.replace("%7E", "~"))
      }
    }
  }

  def normalizeQuery(query: Option[String]) = {
    query.flatMap{ query =>
      query.trim match {
        case Query(params @ _*) => Some(Query(params))
        case query =>
          log.error("query normalization failed: [%s]".format(query))
          Some(Query(query))
      }
    }
  }

  def normalizeFragment(fragment: Option[String]) = {
    fragment.flatMap{ fragment =>
      fragment.trim match {
        case "" => None
        case fragment => Some(normalizeString(fragment, "fragment"))
      }
    }
  }

  def normalizeString(string: String, what: String) = {
    try {
      val decoded = java.net.URLDecoder.decode(string.replace("+", "%20"), UTF8) // java URLDecoder does not replace "+" with a space
      java.net.URLEncoder.encode(decoded, UTF8)
    } catch {
      case e: Exception =>
        log.error("%s normalization failed: [%s]".format(what, string))
        string
    }
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
    var uri = try {
      new java.net.URI(scheme.orNull, userInfo.orNull, host.map(_.toString).orNull, port, updatedPath.orNull, null, null).toString
    } catch {
      case e: Exception => throw new Exception("can't create java.net.URI from raw %s: scheme %s, userInfo %s, host %s, port %s, updatedPath %s".
          format(raw, scheme, userInfo, host, port, updatedPath), e)
    }
    query.foreach{ query => uri = uri + "?" + query }
    fragment.foreach{ fragment => uri = uri + "#" + fragment }
    uri
  }

  override def hashCode() = URI.unapply(this).hashCode()
  override def equals(o: Any) = o match {
    case that: URI if getClass == that.getClass => URI.unapply(this) == URI.unapply(that)
    case _ => false
  }
}

object Host {
  def apply(host: String*) = new Host(host.toSeq)

  def unapplySeq(host: String) = {
    val domain = host.toLowerCase.split("""\.""").toSeq.reverse
    if (domain.size > 0) Some(domain)
    else None
  }
  def unapplySeq(host: Host) = Some(host.domain)
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
  def apply(params: Seq[Param]) = new Query(params)
  def apply(query: String) = new Query(Seq(new Param(query, None)))

  def unapplySeq(query: Query): Option[Seq[Param]] = Some(query.params)
  def unapplySeq(query: String): Option[Seq[Param]] = {
    var pairs = Map.empty[String, Option[String]]
    query.split("&").foreach{
      case "" =>
      case NameValuePair(name, value) => pairs += (name -> value)
    }
    Some(pairs.toSeq.sortBy(_._1).map{ case (k, v) => Param(k, v) }.toSeq)
  }

  object NameValuePair {
    def unapply(param: String): Option[(String, Option[String])] = {
      param.split("=", 2) match {
        case Array(name) => Some((URI.normalizeString(name, "parameter name"), None))
        case Array(name, value) => Some((URI.normalizeString(name, "parameter name"), Some(URI.normalizeString(value, "parameter value"))))
        case _ => Some((URI.normalizeString(param, "parameter"), None))
      }
    }
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
}

case class Param(name: String, value: Option[String]) {
  override def toString() = {
    if (value.isDefined) (name + "=" + value.get) else name
  }

  def decodedValue: Option[String] = value.map{ v => java.net.URLDecoder.decode(v, UTF8) }
}
