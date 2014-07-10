package com.keepit.common.mail

import org.jsoup.Jsoup
import javax.mail.{ Multipart, Part }
import javax.mail.internet.InternetAddress

class GenericMailParser {
  // see http://www.oracle.com/technetwork/java/javamail/faq/index.html#mainbody
  // This makes no attempts to deal with malformed emails.
  def getText(p: Part): Option[String] = {
    if (p.isMimeType("text/*")) {
      Option(p.getContent.asInstanceOf[String]).map {
        case html if p.isMimeType("text/html") => Jsoup.parse(html).text()
        case text => text
      }
    } else if (p.isMimeType("multipart/alternative")) {
      val mp = p.getContent.asInstanceOf[Multipart]
      (0 until mp.getCount).map(mp.getBodyPart).foldLeft(None: Option[String]) { (text, bp) =>
        if (bp.isMimeType("text/plain"))
          getText(bp) orElse text
        else
          text orElse getText(bp)
      }
    } else if (p.isMimeType("multipart/*")) {
      val mp = p.getContent.asInstanceOf[Multipart]
      (0 until mp.getCount).map(mp.getBodyPart).foldLeft(None: Option[String]) { _ orElse getText(_) }
    } else {
      None
    }
  }

  def getAddr(address: javax.mail.Address): String =
    address.asInstanceOf[InternetAddress].getAddress
}
