package com.keepit.rover.document.tika

import org.apache.tika.sax.XHTMLContentHandler.XHTML

import org.apache.tika.sax.{ RoverLinkBuilder, Link }
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

import scala.collection.mutable

/**
 * Custom implementation of [[org.apache.tika.sax.LinkContentHandler]] that also handles link elements
 *
 * @param collapseWhitespaceInAnchor whether to collapse whitespace
 */
class RoverLinkContentHandler(collapseWhitespaceInAnchor: Boolean = false) extends DefaultHandler {

  private val builderStack = mutable.Stack[RoverLinkBuilder]()
  private val linksStack = mutable.Stack[Link]()

  def links = linksStack.toList

  override def startElement(uri: String, local: String, name: String, attributes: Attributes) {
    if (uri == XHTML) {
      if (local == "a") {
        val builder = new RoverLinkBuilder("a")
        builder.setURI(attributes.getValue("", "href"))
        builder.setTitle(attributes.getValue("", "title"))
        builder.setRel(attributes.getValue("", "rel"))
        builderStack.push(builder)
      } else if (local == "link") {
        val builder = new RoverLinkBuilder("link")
        builder.setURI(attributes.getValue("", "href"))
        builder.setTitle(attributes.getValue("", "title"))
        builder.setRel(attributes.getValue("", "rel"))
        builderStack.push(builder)
      } else if (local == "img") {
        val builder = new RoverLinkBuilder("img")
        builder.setURI(attributes.getValue("", "src"))
        builder.setTitle(attributes.getValue("", "title"))
        builder.setRel(attributes.getValue("", "rel"))
        builderStack.push(builder)

        val alt: String = attributes.getValue("", "alt")
        if (alt != null) {
          val ch = alt.toCharArray
          characters(ch, 0, ch.length)
        }
      }
    }
  }

  override def characters(ch: Array[Char], start: Int, length: Int) {
    for (builder <- builderStack) {
      builder.characters(ch, start, length)
    }
  }

  override def ignorableWhitespace(ch: Array[Char], start: Int, length: Int) {
    characters(ch, start, length)
  }

  override def endElement(uri: String, local: String, name: String) {
    if (uri == XHTML) {
      if (local == "a" || local == "img" || local == "link") {
        linksStack.push(builderStack.pop().getLink(collapseWhitespaceInAnchor))
      }
    }
  }

}
