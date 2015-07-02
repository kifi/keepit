package org.apache.tika.sax

/**
 * A dummy, public link builder so that the [[LinkContentHandler]] (which is package-local) can be extended by
 * [[com.keepit.rover.document.tika.RoverLinkContentHandler]]
 *
 * @param tpe The type of the link to build
 */
class RoverLinkBuilder(tpe: String) extends LinkBuilder(tpe)
