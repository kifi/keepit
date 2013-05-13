package com.keepit.search.index

import com.keepit.common.time._
import com.keepit.search.graph.URIList
import com.keepit.search.line.LineField
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexableField
import org.apache.lucene.util.Attribute
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.util.BytesRef

trait FieldDecoder {
  def apply(indexableField: IndexableField): String = {
    val field = indexableField.asInstanceOf[Field]
    Option(field.stringValue) match {
      case Some(string) => "string{%s}".format(string)
      case None =>
        Option(field.tokenStreamValue) match {
          case Some(ts) =>
            decodeTokenStream(ts).map{ case (term, pos, payload) =>
              payload match {
                case "" => "%s @%d".format(term, pos)
                case _ => "%s @%d [%s]".format(term, pos, payload)
              }
            }.mkString("tokenStream{", " / " , "}")
          case _ => "unable to decode"
        }
    }
  }

  def decodeTokenStream(ts: TokenStream): ArrayBuffer[(String, Int, String)] = {
    val termAttr = getAttribute(ts, classOf[CharTermAttribute])
    val posIncrAttr = getAttribute(ts, classOf[PositionIncrementAttribute])
    val payloadAttr = getAttribute(ts, classOf[PayloadAttribute])
    val out = ArrayBuffer.empty[(String, Int, String)]
    var pos = 0

    ts.reset()
    while (ts.incrementToken()) {
      val term = termAttr.map(a => new String(a.buffer, 0, a.length)).getOrElse("")
      pos += posIncrAttr.map(a => a.getPositionIncrement).getOrElse(0)
      val payload = payloadAttr.map(a => a.getPayload()).map(p => decodePayload(p)).getOrElse("")
      out.append((term, pos, payload))
    }
    out
  }

  def decodePayload(payload: BytesRef): String = {
    payload.bytes.slice(payload.offset, payload.offset + payload.length).grouped(4).map{ toHexString(_) }.mkString(" ")
  }

  def toHexString(bytes: Array[Byte]) = bytes.foldLeft(""){ (s, b) => s + "%02X".format(b.toInt & 0xFF) }

  def getAttribute[A <: Attribute](ts: TokenStream, clazz: Class[A]) = {
    ts.hasAttribute(clazz) match {
      case true => Some(ts.getAttribute(clazz))
      case _ => None
    }
  }
}

object DocUtil {

  object TextFieldDecoder extends FieldDecoder

  object IdValueFieldDecoder extends FieldDecoder

  object URIListDecoder extends FieldDecoder {
    override def decodePayload(payload: BytesRef) = {
      var seqno = -1
      def toString(ids: Array[Long], timestamps: Array[Long]) = {
        ids.zip(timestamps).map{ case (id, timestamp) =>
          seqno += 1
          "#%d: %d [%s]".format(seqno, id, new DateTime(URIList.unitToMillis(timestamp), DEFAULT_DATE_TIME_ZONE).toStandardTimeString)
        }.mkString(", ")
      }
      val payloadBuffer = new Array[Byte](payload.length)
      System.arraycopy(payload.bytes, payload.offset, payloadBuffer, 0, payload.length)
      val uriList = URIList(payloadBuffer)
      val printable = toString(uriList.ids, uriList.createdAt)
      "version=%d [%s]".format(uriList.version, printable)
    }
  }

  object LineFieldDecoder extends FieldDecoder {
    override def apply(indexableField: IndexableField): String = {
      val field = indexableField.asInstanceOf[Field]
      Option(field.tokenStreamValue) match {
        case Some(ts) =>
          decodeTokenStream(ts).map{ case (term, pos, payload) =>
            payload match {
              case "" => "#%d: %s @%d".format(pos / LineField.MAX_POSITION_PER_LINE, term, pos % LineField.MAX_POSITION_PER_LINE)
              case _ => "#%d: %s @%d [%s]".format(pos / LineField.MAX_POSITION_PER_LINE, term, pos % LineField.MAX_POSITION_PER_LINE, payload)
            }
          }.mkString(" / ")
        case _ => "unable to decode"
      }
    }
  }
}