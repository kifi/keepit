package test

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.util.Version
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.index.DefaultAnalyzer

object QueryTest extends App {

  val parser = new QueryParser(DefaultAnalyzer.forParsing, DefaultAnalyzer.forParsingWithStemmer) with DefaultSyntax
  val query = parser.parse("1.0")
  println(query.toString)
}