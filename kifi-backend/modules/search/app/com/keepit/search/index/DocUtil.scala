package com.keepit.search.index

import java.nio.{ByteBuffer, LongBuffer}
import java.nio.charset.StandardCharsets

import com.keepit.search.util.LongArraySet
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexableField
import org.apache.lucene.util.Attribute
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
            decodeTokenStream(ts).map {
              case (term, pos, payload) =>
                payload match {
                  case "" => "%s @%d".format(term, pos)
                  case _ => "%s @%d [%s]".format(term, pos, payload)
                }
            }.mkString("tokenStream{", " / ", "}")
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

    try {
      ts.reset()
      while (ts.incrementToken()) {
        val term = termAttr.map(a => new String(a.buffer, 0, a.length)).getOrElse("")
        pos += posIncrAttr.map(a => a.getPositionIncrement).getOrElse(0)
        val payload = payloadAttr.map(a => a.getPayload()).map(p => decodePayload(p)).getOrElse("")
        out.append((term, pos, payload))
      }
      ts.end()
    } finally {
      ts.close()
    }
    out
  }

  def decodePayload(payload: BytesRef): String = {
    payload.bytes.slice(payload.offset, payload.offset + payload.length).grouped(4).map { toHexString(_) }.mkString(" ")
  }

  def toHexString(bytes: Array[Byte]) = bytes.foldLeft("") { (s, b) => s + "%02X".format(b.toInt & 0xFF) }

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

  object LineFieldDecoder extends FieldDecoder {
    override def apply(indexableField: IndexableField): String = {
      val field = indexableField.asInstanceOf[Field]
      Option(field.tokenStreamValue) match {
        case Some(ts) =>
          decodeTokenStream(ts).map {
            case (term, pos, payload) =>
              payload match {
                case "" => "#%d: %s @%d".format(pos / LineField.MAX_POSITION_PER_LINE, term, pos % LineField.MAX_POSITION_PER_LINE)
                case _ => "#%d: %s @%d [%s]".format(pos / LineField.MAX_POSITION_PER_LINE, term, pos % LineField.MAX_POSITION_PER_LINE, payload)
              }
          }.mkString(" / ")
        case _ => "unable to decode"
      }
    }
  }

  def binaryDocValFieldDecoder(decode: (Array[Byte], Int, Int) => String): FieldDecoder = new FieldDecoder {
    override def apply(indexableField: IndexableField): String = {
      val binaryValue = indexableField.binaryValue
      decode(binaryValue.bytes, binaryValue.offset, binaryValue.length)
    }
  }

  val stringDocValFieldDecoder = binaryDocValFieldDecoder(new String(_, _, _, StandardCharsets.UTF_8))

  def toLongBuffer(ref: BytesRef): LongBuffer = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length).asLongBuffer().asReadOnlyBuffer()
  def toLongSet(ref: BytesRef): Set[Long] = toLongBuffer(ref).array().toSet
}

object LongBufferUtil {
  @inline def exists(buffer: LongBuffer)(p: Long => Boolean): Boolean = {
    buffer.rewind()
    while (buffer.hasRemaining) {
      if (p(buffer.get())) return true
    }
    false
  }

  @inline def contains(buffer: LongBuffer)(value: Long): Boolean = exists(buffer)(value == _)
  @inline def intersect(buffer: LongBuffer)(values: LongArraySet): Boolean = exists(buffer)(values.findIndex(_) >= 0)

  @inline def foreach(buffer: LongBuffer)(block: Long => Unit): Unit = {
    buffer.rewind()
    while (buffer.hasRemaining) {
      block(buffer.get())
    }
  }
}
