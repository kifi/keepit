package com.keepit.scraper.handler
import org.xml.sax.ContentHandler
import org.apache.tika.sax.ContentHandlerDecorator
import org.xml.sax.Attributes
import org.apache.tika.parser.html.DefaultHtmlMapper

class YoutubeHandler(handler: ContentHandler) extends ContentHandlerDecorator(handler) {
  
  var inCommentText = Int.MaxValue
  var nestLevel = 0
  
  override def startElement(uri: String, localName: String, qName: String, atts: Attributes) {
    nestLevel += 1
    println(localName+":"+qName)
    localName match {
      case "div" =>
        val classIdx = atts.getIndex("class")
        if (classIdx >= 0) { 
          atts.getValue(classIdx) match {
            case "comment-text" =>
              if (inCommentText == Int.MaxValue) inCommentText = nestLevel
            case _ =>
          }
        }
        super.startElement(uri, localName, qName, atts)
      case _ => super.startElement(uri, localName, qName, atts)
    }
  }
  
  override def endElement(uri: String, localName: String, qName: String) {
    nestLevel -= 1
    localName match {
      case "div" =>
        super.endElement(uri, localName, qName)
        if (nestLevel <= inCommentText) inCommentText = Int.MaxValue
      case _ => super.endElement(uri, localName, qName)
    }
  }

  override def characters(ch: Array[Char], start: Int, length: Int) {
    if (inCommentText <= nestLevel) {
      handler.characters(ch, start, length)
//      val str = new String(ch, start, length)
//      println(str)
    }
  }
}

class YoutubeHtmlMapper extends DefaultHtmlMapper {
  override def mapSafeElement(name: String) = {
    name.toLowerCase match {
      case "div" => "div"
      case _ =>super.mapSafeElement(name)
    }
  }
  
  override def mapSafeAttribute(elementName: String, attributeName: String) = {
    (elementName.toLowerCase, attributeName.toLowerCase) match {
      case ("div", "class") => "class"
      case _ => super.mapSafeAttribute(elementName, attributeName)
    }
  }
}

