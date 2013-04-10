package com.keepit.search.query.parser

import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.Query
import scala.util.matching.Regex.Match
import scala.collection.mutable.ArrayBuffer
import scala.annotation.tailrec

object DefaultSyntax {
  val queryFields = Set("", "site")

  val spacesRegex = """\p{Zs}+""".r
  val qualifierRegex = """([+-])[^\p{Zs}]""".r
  val fieldRegex = """(\w+):\p{Zs}*""".r
  val termRegex = """([^\p{Zs}]+)[\p{Zs}$]*""".r
  val quotedTermRegex = """\"((([^\"])|(\"[^\p{Zs}]))*)\"([\p{Zs}]|$)""".r
  val endOfQueryRegex = """($)""".r

  case class QuerySpec(occur: Occur, field: String, term: String, quoted: Boolean)
}

trait DefaultSyntax extends QueryParser {
  import DefaultSyntax._

  override val fields = queryFields

  private[this] var inputText: CharSequence = ""
  private[this] var buf: CharSequence = ""

  private def removeLeadingSpaces(queryText: CharSequence): CharSequence = {
    (spacesRegex findPrefixMatchOf queryText) match {
      case Some(spacesMatch) => spacesMatch.after
      case _ => queryText
    }
  }

  private def parseQualifier(defaultOccur: Occur = SHOULD): Occur = {
    (qualifierRegex findPrefixMatchOf buf) match {
      case Some(qualifierMatch) =>
        buf = qualifierMatch.after(1)
        qualifierMatch.group(1) match {
          case "+" => MUST
          case "-" => MUST_NOT
          case _ => SHOULD
        }
      case None => defaultOccur
    }
  }

  private def parseFieldName(): String = {
    (fieldRegex findPrefixMatchOf buf) match {
      case Some(fieldMatch) =>
        val fieldName = fieldMatch.group(1)
        if (fields.contains(fieldName)) {
          buf = fieldMatch.after
          fieldName
        } else {
          ""
        }
      case None => ""
    }
  }

  private def getTerm(m: Match): String = {
    buf = m.after
    m.group(1)
  }

  def parseQueryTerm(): String = {
    (termRegex findPrefixMatchOf buf) match {
      case Some(m) => getTerm(m)
      case _ =>
        (endOfQueryRegex findPrefixMatchOf buf) match {
          case Some(m) => ""
          case _ => throw new QueryParserException(s"failed to parse terms before the end of query input=[${inputText}] buf=[${buf}]")
        }
    }
  }

  def parseQuotedQueryTerm(): String = {
    (quotedTermRegex findPrefixMatchOf buf) match {
      case Some(m) => getTerm(m)
      case _ => ""
    }
  }

  private def parseFieldQuery(): QuerySpec = {
    val defaultOccur = parseQualifier()
    val field = parseFieldName()
    val occur = parseQualifier(defaultOccur)

    var term = parseQuotedQueryTerm()
    var quoted = false

    if (term.length > 0) {
      quoted = true
    } else {
      term = parseQueryTerm()
    }

    QuerySpec(occur, field, term, quoted)
  }

  @tailrec
  private def parse(result: List[QuerySpec]): List[QuerySpec] = {
    buf match {
      case "" => result.reverse
      case _ => parse(parseFieldQuery()::result)
    }
  }

  override def parse(queryText: CharSequence): Option[Query] = {
    if (queryText == null) {
      None
    } else {
      inputText = queryText
      buf = removeLeadingSpaces(queryText)
      val querySpecList = parse(Nil)
      val clauses = querySpecList.foldLeft(ArrayBuffer.empty[BooleanClause]) { (clauses, spec) =>
        val query = getFieldQuery(spec.field, spec.term, spec.quoted)
        query.foreach{ query => clauses += new BooleanClause(query, spec.occur) }
        clauses
      }
      getBooleanQuery(clauses)
    }
  }
}

