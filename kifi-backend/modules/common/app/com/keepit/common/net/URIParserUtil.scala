package com.keepit.common.net

import com.keepit.common.strings.UTF8
import java.net.{ URLEncoder, URLDecoder }

object URIParserUtil {

  private[this] val controls = "\u0001\u0002\u0003\u0005\u0006\u0007\u0008\u0009\u000a\u000b\u000d\u000e\u000f\u0010" +
    "\u0011\u0012\u0013\u0015\u0016\u0017\u0018\u0019\u001a\u001b\u001d\u001e\u001f\u007f\u0080\u0081\u0082\u0083" +
    "\u0084\u0085\u0086\u0087\u0088\u0089\u008a\u008b\u008c\u008d\u008e\u008f\u0090\u0091\u0092\u0093\u0094\u0095" +
    "\u0096\u0097\u0098\u0099\u009a\u009b\u009c\u009d\u009e\u009f\u00a0"
  private[this] val space = " "
  private[this] val genDelims = ":/?#[]@"
  private[this] val subDelims = "!$&'()*+,;="
  private[this] val others = """`"^{}<>\|%"""
  private[this] val allSymbols = controls + space + genDelims + subDelims + others

  val pathReservedChars: Set[Char] = Set.empty ++ controls ++ space ++ genDelims ++ others - '@' - ':'
  val paramNameReservedChars: Set[Char] = Set.empty ++ controls ++ genDelims ++ others - '@' - ':' - '/' - '?'
  val paramValueReservedChars: Set[Char] = Set.empty ++ controls ++ genDelims ++ others - '@' - ':' - '/' - '?'
  val fragmentReservedChars: Set[Char] = Set.empty ++ controls ++ space ++ genDelims ++ others - '@' - ':' - '/' - '?'
  val encodingMap: Map[Char, String] = allSymbols.map(c => (c -> encodeChar(c))).toMap

  private[this] val _percentEncodeRe = """(\%\p{XDigit}\p{XDigit})+""".r

  def encodeChar(c: Char): String = {
    c match {
      case ' ' => "%20"
      case _ => URLEncoder.encode(c.toString, UTF8)
    }
  }

  def encode(string: String, symbols: Set[Char]): String = {
    def replaceChar(s: String, c: Char) = s.replace(c.toString, encodingMap(c))

    val tmp = if (symbols.contains('%')) replaceChar(string, '%') else string // replace % first
    symbols.foldLeft(tmp) { (s, c) =>
      if (c == '%') s else replaceChar(s, c)
    }
  }

  def encodeNonASCII(string: String, symbols: Set[Char]): String = {
    val builder = new StringBuilder()
    string.foreach { c =>
      if (symbols.contains(c)) builder ++= encodingMap(c)
      else if (c <= 255) builder += c // ASCII
      else builder ++= encodeChar(c) // non-ASCII
    }
    builder.toString
  }

  def decodePercentEncode(string: String) = {
    _percentEncodeRe.replaceAllIn(string, { m =>
      URLDecoder.decode(m.matched, UTF8).replace("""\""", """\\""").replace("$", """\$""")
    })
  }

  def normalizePort(scheme: Option[String], port: Int): Int = (scheme, port) match {
    case (Some("http"), 80) => -1
    case (Some("https"), 443) => -1
    case _ => port
  }

  def normalizePath(components: Seq[String]) = {
    var stack = List.empty[String]

    def addComponent(c: String) = {
      stack = stack match {
        case "" :: tail => c :: tail
        case s => c :: stack
      }
    }

    components.foreach {
      case ".." => if (stack.nonEmpty) stack = stack.tail
      case "." => addComponent("")
      case component => addComponent(component)
    }

    stack.reverse
  }

  def normalizePathComponent(component: String) = encode(decodePercentEncode(component), pathReservedChars)

  def normalizeParams(params: Seq[Param]): Seq[Param] = {
    var pairs = Map.empty[String, Param]
    params.filterNot(_.isEmpty).foreach { param =>
      pairs += (param.name -> param)
    }
    pairs.toSeq.sortBy(_._1).map { case (name, param) => param }.toSeq
  }

  def normalizeParamName(name: String) = encode(decodePercentEncode(name.replace('+', ' ')), paramNameReservedChars).replace(' ', '+')

  def normalizeParamValue(value: String) = encode(decodePercentEncode(value.replace('+', ' ')), paramValueReservedChars).replace(' ', '+')

  def normalizeFragment(fragment: String) = encode(decodePercentEncode(fragment), fragmentReservedChars)
}
