package com.keepit.search.index

import com.keepit.common.CollectionHelpers
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.net._
import com.keepit.model.User
import com.keepit.typeahead.PrefixFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import java.io.IOException
import java.io.Reader
import com.keepit.common.logging.Logging

object Indexable {
  val textFieldType: FieldType = {
    new FieldType(TextField.TYPE_NOT_STORED)
  }

  val textFieldTypeNoNorm: FieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
  }

  val keywordFieldType: FieldType = {
    new FieldType(StringField.TYPE_NOT_STORED)
  }

  val dataPayloadFieldType: FieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
  }

  val MAX_BINARY_FIELD_LENGTH = 32766 // DON'T CHANGE THESE CONSTS UNLESS YOU KNOW WHAT YOU ARE DOING
  val MAX_BINARY_FIELD_LENGTH_MINUS1 = 32765
  val END_OF_BINARY_FIELD = 0.toByte

  def addNumberSuffix(fieldName: String, n: Int): String = {
    if (n < 0) throw new IllegalArgumentException(s"suffix number must be non-negative, input = ${n}")
    if (n == 0) fieldName else s"${fieldName}_${n}"
  }

  class IteratorTokenStream[A](iterator: Iterator[A], toToken: (A => String)) extends TokenStream {
    val termAttr = addAttribute(classOf[CharTermAttribute])
    val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute]);

    @throws(classOf[IOException])
    override def incrementToken(): Boolean = {
      clearAttributes()
      iterator.hasNext match {
        case true =>
          val termText = toToken(iterator.next)
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          true
        case false => false
      }
    }
  }

  class DataPayloadTokenStream(termText: String, data: Array[Byte]) extends TokenStream {
    val termAttr = addAttribute(classOf[CharTermAttribute])
    val payloadAttr = addAttribute(classOf[PayloadAttribute])
    val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
    var returnToken = true;

    @throws(classOf[IOException])
    override def incrementToken(): Boolean = {
      returnToken match {
        case true =>
          payloadAttr.setPayload(new BytesRef(data))
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          returnToken = false
          true
        case false => false
      }
    }
  }

  def getFieldDecoder(decoders: Map[String, FieldDecoder])(fieldName: String): FieldDecoder = {
    decoders.get(fieldName) match {
      case Some(decoder) => decoder
      case _ => fieldName match {
        case Indexer.idValueFieldName => DocUtil.IdValueFieldDecoder
        case _ => DocUtil.TextFieldDecoder
      }
    }
  }
}

trait Indexable[T, S] extends Logging {
  import Indexable._

  val sequenceNumber: SequenceNumber[S]
  val id: Id[T]
  val isDeleted: Boolean

  lazy val idTerm = new Term(Indexer.idFieldName, id.toString)

  def buildDocument: Document = {
    val doc = new Document()
    doc.add(buildKeywordField(Indexer.idFieldName, idTerm.text()))
    doc.add(buildIdValueField(id))
    doc
  }

  protected def buildKeywordField(fieldName: String, keyword: String): Field = {
    new Field(fieldName, keyword, keywordFieldType)
  }

  protected def buildTextField(fieldName: String, fieldValue: String): Field = {
    new Field(fieldName, fieldValue, textFieldType)
  }

  protected def buildTextField(fieldName: String, fieldValue: String, analyzer: Analyzer): Field = {
    val ts = analyzer.createLazyTokenStream(fieldName, fieldValue)
    new Field(fieldName, ts, textFieldType)
  }

  protected def buildTextField(fieldName: String, fieldValue: Reader, analyzer: Analyzer): Field = {
    val ts = analyzer.createLazyTokenStream(fieldName, fieldValue)
    new Field(fieldName, ts, textFieldType)
  }

  protected def buildPrefixField(fieldName: String, fieldValue: String, maxPrefixLength: Int, minPrefixLength: Int = 1): Field = {
    val tokens = PrefixFilter.tokenize(fieldValue)
    val prefixes = for {
      token <- tokens
      prefixLength <- minPrefixLength to maxPrefixLength
    } yield {
      token.take(prefixLength)
    }
    val uniquePrefixes = CollectionHelpers.dedupBy(prefixes)(identity)
    buildIteratorField(fieldName, uniquePrefixes.toIterator)(identity)
  }

  def buildIdValueField(typedId: Id[T]): Field = buildIdValueField(Indexer.idValueFieldName, typedId)
  def buildIdValueField[V](field: String, typedId: Id[V]): Field = new NumericDocValuesField(field, typedId.id)

  def buildLongValueField(field: String, v: Long): Field = new NumericDocValuesField(field, v)

  def buildDataPayloadField(term: Term, data: Array[Byte]): Field = {
    new Field(term.field(), new DataPayloadTokenStream(term.text(), data), dataPayloadFieldType)
  }

  def buildIteratorField[A](fieldName: String, iterator: Iterator[A], fieldType: FieldType = textFieldTypeNoNorm)(toToken: (A => String)) = {
    new Field(fieldName, new IteratorTokenStream(iterator, toToken), fieldType)
  }

  def buildBinaryDocValuesField(fieldName: String, bytes: Array[Byte]): Field = {
    new BinaryDocValuesField(fieldName, new BytesRef(bytes))
  }

  def buildStringDocValuesField(fieldName: String, value: String): Field = {
    new BinaryDocValuesField(fieldName, new BytesRef(value))
  }

  def buildExtraLongBinaryDocValuesField(fieldName: String, bytes: Array[Byte]): Seq[Field] = {
    val batchSize = MAX_BINARY_FIELD_LENGTH_MINUS1
    val batches = bytes.grouped(batchSize).toArray
    val rounds = batches.size

    if (rounds > 1) log.warn(s"\n==\nbuilding extra long binary docValues field: num of rounds: ${rounds}")

    batches.zipWithIndex.map {
      case (subBytes, idx) =>
        val currentFieldName = addNumberSuffix(fieldName, idx)
        if (idx == rounds - 1) new BinaryDocValuesField(currentFieldName, new BytesRef(subBytes)) // nothing left
        else new BinaryDocValuesField(currentFieldName, new BytesRef(subBytes :+ END_OF_BINARY_FIELD)) // the extra byte indicates we have more
    }
  }

  def buildDomainFields(url: String, siteField: String, homePageField: String): Seq[Field] = {
    URI.parse(url).map {
      case uri =>
        // index domain name
        val domainName = uri.host.collect {
          case Host(domain @ _*) if domain.nonEmpty =>
            buildIteratorField(siteField, (1 to domain.size).iterator) { n => domain.take(n).reverse.mkString(".") }
        }

        // home page
        val homePage = Some(uri) collect {
          case URI(_, _, Some(Host(domain @ _*)), _, path, None, None) if (path.isEmpty || path == Some("/")) =>
            buildTextField(homePageField, domain.reverse.mkString(" "), DefaultAnalyzer.defaultAnalyzer)
        }

        Seq(domainName, homePage).flatten
    } getOrElse Seq.empty
  }

  def urlToIndexableString(url: String): Option[String] = {
    URI.parse(url).toOption.map { u =>
      val host = u.host match {
        case Some(Host(domain @ _*)) => domain.mkString(" ")
        case _ => ""
      }
      val path = u.path.map { p =>
        URIParserUtil.pathReservedChars.foldLeft(URIParserUtil.decodePercentEncode(p)) { (s, c) => s.replace(c.toString, " ") }
      }
      host + " " + path
    }
  }
}

