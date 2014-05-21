package com.keepit.eliza.util

import scala.util.matching.Regex.Match
import java.net.URLDecoder

abstract class MessageSegment(val kind: String, val txt: String) //for use in templates since you can't match on type (it seems)
case class TextLookHereSegment(override val txt: String, pageText: String) extends MessageSegment("tlh", txt)
case class ImageLookHereSegment(override val txt: String, imgUrl: String) extends MessageSegment("ilh", txt)
case class TextSegment(override val txt: String) extends MessageSegment("txt", txt)

object MessageFormatter {

  private[this] val lookHereRe = """\[([^\]]*(?:(?<=\\)\][^\]]*)*)\](\(x-kifi-sel:([^)]*(?:(?<=\\)\)[^)]*)*)\))""".r

  /**
   * Formats [[com.keepit.eliza.model.Message.messageText]] (in a markdown-based format) as plain text.
   * Removes "look here" links, preserving only the link text.
   */
  def toText(messageText: String): String = {
    lookHereRe.replaceAllIn(messageText, (m: Match) => m.group(1))
  }

  /**
   * Parses message segments from [[com.keepit.eliza.model.Message.messageText]] (in a markdown-based format)
   */
  def parseMessageSegments(msg: String): Seq[MessageSegment] = {

    def parseSegment(m: Match) = {
      val segments = m.group(3).split('|')
      val kind = segments.head
      val payload = URLDecoder.decode(segments.last, "UTF-8").replace(raw"\)",")")
      val text = m.group(1).replace(raw"\]","]")
      kind match {
        case "i" => ImageLookHereSegment(text, payload)
        case "r" => TextLookHereSegment(text, payload)
        case _ => throw new Exception("Unknown look-here type: " + kind)
      }
    }

    try {
      val (position, segments) = lookHereRe.findAllMatchIn(msg).foldLeft((0,Seq[MessageSegment]())){ (acc, m) =>
        val (currPos, seq) = acc
        val lookHereSegment = parseSegment(m)
        (m.end, if (m.start > currPos) seq :+ TextSegment(msg.substring(currPos, m.start)) :+ lookHereSegment
        else seq :+ lookHereSegment)
      }
      val ending = TextSegment(msg.substring(position))
      if (ending.txt.length > 0) segments :+ ending else segments
    } catch {
      case t: Throwable => {
        throw new Exception(s"Exception during parsing of message $msg. Exception was $t")
      }
    }
  }
}
