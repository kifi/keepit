package com.keepit.common.admin

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import play.api.Play
import play.api.Play.current

case class DouglasAdamsQuote(quote: Seq[String], cite: String)

object DouglasAdamsQuotes {

  lazy val quotes: Seq[DouglasAdamsQuote] = try {
    val lines = io.Source.fromURL(Play.resource("/public/html/quotes.txt").get)("UTF-8").getLines()
    var lastQuote: Option[DouglasAdamsQuote] = None
    val quotes = mutable.ArrayBuffer[DouglasAdamsQuote]()
    lines.foreach { line =>
      lastQuote = lastQuote match {
        case Some(quote) if line startsWith "<a" =>
          quotes += quote.copy(cite = line.replaceAll("""<a href="/work/quotes/[0-9]*">""", "").replaceAll("</a>", ""))
          None
        case _ => Some(DouglasAdamsQuote(Seq(""""%s"""".format(line).split("<br />"): _*), "Unknown"))
      }
    }
    quotes.toIndexedSeq
  } catch {
    case NonFatal(e) => Seq(DouglasAdamsQuote(Seq("No quotes for you :-("), e.toString))
  }

  private val rand = new Random()

  def random: DouglasAdamsQuote = quotes(rand.nextInt(quotes.size))

}
