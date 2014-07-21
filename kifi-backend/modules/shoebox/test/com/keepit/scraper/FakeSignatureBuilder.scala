package com.keepit.scraper

class FakeSignatureBuilder(windowSize: Int = 20) extends Signature.Builder(windowSize) {

  override protected def tokenize(text: String)(addTerm: (Array[Char], Int) => Unit): Unit = {
    text.split("[\\s\\.]+").foreach { token =>
      val charArray = token.toLowerCase.toArray
      addTerm(charArray, charArray.length)
    }
  }

}
