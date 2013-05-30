package com.keepit.common.admin

import java.util.Random
import scala.collection.mutable.{MutableList => MList}
import play.api.Play
import play.api.Play.current

case class DouglasAdamsQuote(quote: List[String], cite: String)

object DouglasAdamsQuotes {

  lazy val qoutes: List[DouglasAdamsQuote] = try {
    val lines = io.Source.fromURL(Play.resource("/public/html/quotes.txt").get).getLines.toList
    var lastQuote: Option[DouglasAdamsQuote] = None
    val quotes = MList[DouglasAdamsQuote]()
    lines.foreach { line =>
      lastQuote = lastQuote match {
        case Some(quote) if(line.startsWith("<a")) =>
          quotes += quote.copy(cite = line.replaceAll("""<a href="/work/quotes/[0-9]*">""", "").replaceAll("</a>", ""))
          None
        case _ => Some(DouglasAdamsQuote(List(""""%s"""".format(line).split("<br />"): _*), "Unknown"))
      }
    }
    quotes.toList
  } catch {
    case e: RuntimeException =>
      List(DouglasAdamsQuote(List("No quotes for you :-("), e.toString))
  }

  def random: DouglasAdamsQuote = qoutes(new Random().nextInt(qoutes.size))

}
