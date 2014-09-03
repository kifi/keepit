package com.keepit.search.engine.parser

import com.keepit.classify.Domain
import com.keepit.search.Lang
import com.keepit.search.engine.query._
import com.keepit.search.index.Analyzer
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.parser.{ QueryParser, QueryParserException, QuerySpec }
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.{ BooleanClause, BooleanQuery, PhraseQuery, Query, TermQuery }

import scala.collection.mutable.ArrayBuffer

object KQueryExpansion {
  private[this] val langsToUseBoolean = Set[Lang](Lang("ja"))

  def useBooleanForPhrase(lang: Lang) = langsToUseBoolean.contains(lang)
}

trait KQueryExpansion extends QueryParser {

  val lang: Lang // primary language

  val altAnalyzer: Option[Analyzer]
  val altStemmingAnalyzer: Option[Analyzer]

  val siteBoost: Float
  val concatBoost: Float

  val textQueries: ArrayBuffer[KTextQuery] = ArrayBuffer()

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    field.toLowerCase match {
      case "tag" => getTagQuery(queryText)
      case "site" => getSiteQuery(queryText)
      case "media" => getMediaQuery(queryText)
      case _ => getTextQuery(queryText, quoted)
    }
  }

  protected def getTagQuery(tag: String): Option[Query] = if (tag != null) Option(KTagQuery(tag)) else None

  protected def getSiteQuery(domain: String): Option[Query] = if (domain != null) Option(KSiteQuery(domain)) else None

  protected def getMediaQuery(media: String): Option[Query] = if (media != null) Option(KMediaQuery(media)) else None

  private def mayConvertQuery(query: Query, language: Lang): Query = {
    if (KQueryExpansion.useBooleanForPhrase(language)) {
      query match {
        case phrase: PhraseQuery =>
          val terms = phrase.getTerms()
          val booleanQuery = new BooleanQuery(false)
          terms.foreach { term => booleanQuery.add(new TermQuery(term), SHOULD) }
          booleanQuery.add(query, SHOULD)
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

    def addSiteQuery(baseQuery: KTextQuery, queryText: String) {
      if (Domain.isValid(queryText)) baseQuery.addQuery(KSiteQuery(queryText), siteBoost)
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

    val textQuery = new KTextQuery
    textQueries += textQuery

    super.getFieldQuery("t", queryText, quoted).foreach { q =>
      textQuery.terms = extractTerms(q)
      val query = if (quoted) q else mayConvertQuery(q, lang)
      textQuery.addQuery(query)
      textQuery.addQuery(copyFieldQuery(query, "c"))
      addSiteQuery(textQuery, queryText)
      if (isNumericTermQuery(query) && textQuery.getBoost() >= 1.0f) textQuery.setBoost(0.5f)
    }

    altAnalyzer.foreach { alt =>
      super.getFieldQuery("t", queryText, quoted, alt).foreach { q =>
        if (!equivalent(textQuery.terms, extractTerms(q))) {
          val query = if (quoted) q else mayConvertQuery(q, alt.lang)
          val boost = if (textQuery.isEmpty) 0.1f else 1.0f
          textQuery.addQuery(query)
          textQuery.addQuery(copyFieldQuery(query, "c"))
          textQuery.setBoost(textQuery.getBoost * boost)
        }
      }
    }

    getStemmedFieldQuery("ts", queryText).foreach { q =>
      textQuery.stems = extractTerms(q)
      if (!quoted) {
        val query = mayConvertQuery(q, lang)
        textQuery.addQuery(query)
        textQuery.addQuery(copyFieldQuery(query, "cs"))
      }
    }

    if (!quoted) {
      altStemmingAnalyzer.foreach { alt =>
        getFieldQuery("ts", queryText, false, alt).foreach { q =>
          if (!equivalent(textQuery.stems, extractTerms(q))) {
            val query = mayConvertQuery(q, alt.lang)
            val boost = if (textQuery.isEmpty) 0.1f else 1.0f
            textQuery.addQuery(query)
            textQuery.addQuery(copyFieldQuery(query, "cs"))
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
    val queries = ArrayBuffer.empty[(QuerySpec, KTextQuery)]

    querySpecList.foreach { spec =>
      val query = getFieldQuery(spec.field, spec.term, spec.quoted)
      query match {
        case Some(query) =>
          clauses += new BooleanClause(query, spec.occur)
          query match {
            case textQuery: KTextQuery => queries += ((spec, textQuery))
            case _ => queries += ((spec, null))
          }
        case _ =>
          queries += ((spec, null))
      }
    }

    // if we have too many terms, don't concat terms
    if (concatBoost > 0.0f && clauses.size <= 42) KConcatQueryAdder.addConcatQueries(queries, concatBoost)

    getBooleanQuery(clauses)
  }

  override protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.isEmpty) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = new KBooleanQuery
      clauses.foreach { clause => query.add(clause) }
      Some(query)
    }
  }
}

object KConcatQueryAdder {

  private def addConcatQuery(textQuery: KTextQuery, concat: (String, String), concatBoost: Float): Unit = {
    val (t1, t2) = concat

    textQuery.concatStems += t2

    textQuery.addQuery(new TermQuery(new Term("t", t1)), concatBoost)
    textQuery.addQuery(new TermQuery(new Term("c", t1)), concatBoost)

    textQuery.addQuery(new TermQuery(new Term("ts", t2)), concatBoost)
    textQuery.addQuery(new TermQuery(new Term("cs", t2)), concatBoost)
  }

  private def concat(q1: KTextQuery, q2: KTextQuery): (String, String) = {
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
  def addConcatQueries(queries: ArrayBuffer[(QuerySpec, KTextQuery)], concatBoost: Float) {

    val emptyQuery = new KTextQuery
    var prevTextQuery: KTextQuery = null
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
