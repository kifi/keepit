package com.keepit.search.query.parser

import com.keepit.classify.Domain
import com.keepit.search.Lang
import com.keepit.search.index.Analyzer
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.MediaQuery
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.TextQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer

object QueryExpansion {
  private[this] val langsToUseBoolean = Set[Lang](Lang("ja"))

  def useBooleanForPhrase(lang: Lang) = langsToUseBoolean.contains(lang)
}

trait QueryExpansion extends QueryParser {

  val lang: Lang // primary language

  val altAnalyzer: Option[Analyzer]
  val altStemmingAnalyzer: Option[Analyzer]

  val siteBoost: Float
  val concatBoost: Float

  val textQueries: ArrayBuffer[TextQuery] = ArrayBuffer()

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case "media" => Option(MediaQuery(queryText))
      case _ => getTextQuery(queryText, quoted)
    }
  }

  protected def getSiteQuery(domain: String): Option[Query] = if (domain != null) Option(SiteQuery(domain)) else None

  private def mayConvertQuery(query: Query, language: Lang): Query = {
    if (QueryExpansion.useBooleanForPhrase(language)) {
      query match {
        case phrase: PhraseQuery =>
          val terms = phrase.getTerms()
          val booleanQuery = new BooleanQuery(false)
          terms.foreach { term => booleanQuery.add(new TermQuery(term), SHOULD) }
          booleanQuery
        case _ =>
          query
      }
    } else {
      query
    }
  }

  protected def getTextQuery(queryText: String, quoted: Boolean): Option[Query] = {
    def copyFieldQuery(query: Query, field: String) = {
      query match {
        case null => null
        case query: TermQuery => copy(query, field)
        case query: PhraseQuery => copy(query, field)
        case query: BooleanQuery => copy(query, field)
        case _ => throw new QueryParserException(s"failed to copy query: ${query.toString}")
      }
    }

    def addSiteQuery(baseQuery: TextQuery, queryText: String) {
      if (Domain.isValid(queryText)) baseQuery.addRegularQuery(SiteQuery(queryText), siteBoost)
    }

    def isNumericTermQuery(query: Query): Boolean = query match {
      case q: TermQuery => q.getTerm.text.forall(Character.isDigit)
      case _ => false
    }

    def equivalent(a: Array[Term], b: Array[Term]): Boolean = {
      if (a.length == b.length) {
        var i = 0
        while (i < a.length) {
          if (a(i) != b(i)) return false
          i += 1
        }
        true
      } else {
        false
      }
    }

    val textQuery = new TextQuery
    textQueries += textQuery

    super.getFieldQuery("t", queryText, quoted).foreach { q =>
      textQuery.terms = extractTerms(q)
      val query = if (quoted) q else mayConvertQuery(q, lang)
      textQuery.addRegularQuery(query)
      textQuery.addRegularQuery(copyFieldQuery(query, "c"))
      textQuery.addPersonalQuery(copyFieldQuery(query, "title"))
      addSiteQuery(textQuery, queryText)
      if (isNumericTermQuery(query) && textQuery.getBoost() >= 1.0f) textQuery.setBoost(0.5f)
    }

    altAnalyzer.foreach { alt =>
      super.getFieldQuery("t", queryText, quoted, alt).foreach { q =>
        if (!equivalent(textQuery.terms, extractTerms(q))) {
          val query = if (quoted) q else mayConvertQuery(q, alt.lang)
          val boost = if (textQuery.isEmpty) 0.1f else 1.0f
          textQuery.addRegularQuery(query)
          textQuery.addRegularQuery(copyFieldQuery(query, "c"))
          textQuery.addPersonalQuery(copyFieldQuery(query, "title"))
          textQuery.setBoost(textQuery.getBoost * boost)
        }
      }
    }

    getStemmedFieldQuery("ts", queryText).foreach { q =>
      textQuery.stems = extractTerms(q)
      if (!quoted) {
        val query = mayConvertQuery(q, lang)
        textQuery.addRegularQuery(query)
        textQuery.addRegularQuery(copyFieldQuery(query, "cs"))
        textQuery.addPersonalQuery(copyFieldQuery(query, "title_stemmed"))
      }
    }

    if (!quoted) {
      altStemmingAnalyzer.foreach { alt =>
        getFieldQuery("ts", queryText, false, alt).foreach { q =>
          if (!equivalent(textQuery.stems, extractTerms(q))) {
            val query = mayConvertQuery(q, alt.lang)
            val boost = if (textQuery.isEmpty) 0.1f else 1.0f
            textQuery.addRegularQuery(query)
            textQuery.addRegularQuery(copyFieldQuery(query, "cs"))
            textQuery.addPersonalQuery(copyFieldQuery(query, "title_stemmed"))
            textQuery.setBoost(textQuery.getBoost * boost)
          }
        }
      }
    }

    if (textQuery.isEmpty) None else Some(textQuery)
  }

  private def extractTerms(query: Query): Array[Term] = {
    query match {
      case q: TermQuery => Array(q.getTerm)
      case q: PhraseQuery => q.getTerms
      case _ => Array.empty[Term]
    }
  }

  override protected def buildQuery(querySpecList: List[QuerySpec]): Option[Query] = {
    val clauses = ArrayBuffer.empty[BooleanClause]
    val queries = ArrayBuffer.empty[(QuerySpec, TextQuery)]

    querySpecList.foreach { spec =>
      val query = getFieldQuery(spec.field, spec.term, spec.quoted)
      query match {
        case Some(query) =>
          clauses += new BooleanClause(query, spec.occur)
          query match {
            case textQuery: TextQuery => queries += ((spec, textQuery))
            case _ => queries += ((spec, null))
          }
        case _ =>
          queries += ((spec, null))
      }
    }

    // if we have too many terms, don't concat terms
    if (concatBoost > 0.0f && clauses.size <= 42) ConcatQueryAdder.addConcatQueries(queries, concatBoost)

    getBooleanQuery(clauses)
  }
}

object ConcatQueryAdder {

  private def addConcatQuery(textQuery: TextQuery, concat: (String, String), concatBoost: Float): Unit = {
    val (t1, t2) = concat

    textQuery.concatStems += t2

    textQuery.addRegularQuery(new TermQuery(new Term("t", t1)), concatBoost)
    textQuery.addRegularQuery(new TermQuery(new Term("c", t1)), concatBoost)
    textQuery.addPersonalQuery(new TermQuery(new Term("title", t1)), concatBoost)

    textQuery.addRegularQuery(new TermQuery(new Term("ts", t2)), concatBoost)
    textQuery.addRegularQuery(new TermQuery(new Term("cs", t2)), concatBoost)
    textQuery.addPersonalQuery(new TermQuery(new Term("title_stemmed", t2)), concatBoost)
  }

  private def concat(q1: TextQuery, q2: TextQuery): (String, String) = {
    val sb = new StringBuilder

    def append(terms: Array[Term], off: Int, end: Int): Unit = {
      if (off >= 0 && off < end) {
        var i = off
        while (i < end) {
          sb ++= terms(i).text
          i += 1
        }
      }
    }

    append(q1.terms, 0, q1.terms.length)
    append(q2.terms, 0, q2.terms.length - 1)
    val len = sb.length

    append(q2.terms, q2.terms.length - 1, q2.terms.length)
    val t1 = sb.toString

    sb.setLength(len)
    append(q2.stems, q2.stems.length - 1, q2.stems.length)
    val t2 = sb.toString

    (t1, t2)
  }

  // side effect
  def addConcatQueries(queries: ArrayBuffer[(QuerySpec, TextQuery)], concatBoost: Float) {

    val emptyQuery = new TextQuery
    var prevTextQuery: TextQuery = null
    queries.foreach {
      case (spec, currTextQuery) =>
        if (currTextQuery != null && !spec.quoted && spec.occur == SHOULD) {
          // concat phrase in the current query
          if (currTextQuery.terms.length > 1) {
            val c = concat(emptyQuery, currTextQuery)
            addConcatQuery(currTextQuery, c, concatBoost)
          }
          // concat with the previous query
          if (prevTextQuery != null) {
            val c = concat(prevTextQuery, currTextQuery)
            addConcatQuery(prevTextQuery, c, concatBoost)
            addConcatQuery(currTextQuery, c, concatBoost)
          }
          prevTextQuery = currTextQuery
        } else {
          prevTextQuery = null
        }
    }
  }

}
