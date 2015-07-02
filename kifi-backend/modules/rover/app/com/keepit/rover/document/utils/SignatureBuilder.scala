package com.keepit.rover.document.utils

import java.io.StringReader

import com.keepit.rover.article.Article
import com.keepit.rover.article.content.ArticleContent
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

class SignatureBuilder(windowSize: Int = 20) extends Signature.Builder(windowSize) {

  override protected def tokenize(text: String)(addTerm: (Array[Char], Int) => Unit): Unit = {
    val ts = new StandardTokenizer(new StringReader(text))
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])

    try {
      ts.reset()
      while (ts.incrementToken()) addTerm(termAttr.buffer(), termAttr.length())
      ts.end()
    } finally {
      ts.close()
    }
  }

}

object SignatureBuilder {

  def defaultSignature[A <: Article](articleContent: ArticleContent[A]): Signature = {
    new SignatureBuilder().add(
      Seq(
        articleContent.title.toSeq,
        articleContent.description.toSeq,
        articleContent.content.toSeq,
        articleContent.keywords,
        articleContent.authors.map(_.name)
      ).flatten
    ).build
  }

}
