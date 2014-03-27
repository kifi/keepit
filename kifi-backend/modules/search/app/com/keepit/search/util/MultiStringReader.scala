package com.keepit.search.util

import java.io.Reader
import scala.math.min

class MultiStringReader(lines: Array[String]) extends Reader {
  private[this] var lineNum: Int = 0
  private[this] var linePos: Int = 0

  override def read(cbuf: Array[Char], off: Int, len: Int): Int = {
    if (len < 0) throw new IllegalArgumentException("buffer length is not positive integer")

    var ptr = off
    var remain = len

    while (lineNum < lines.length) {
      val cur = lines(lineNum)
      if (linePos < cur.length) {
        val amount = min(cur.length - linePos, remain)
        cur.getChars(linePos, linePos + amount, cbuf, ptr)
        linePos += amount
        ptr += amount
        remain -= amount

        if (remain == 0) return len // cbuf is filled
      }
      lineNum += 1
      linePos = 0
    }
    if (ptr - off == 0) -1 else ptr - off
  }

  override def read(): Int = {
    while (lineNum < lines.length) {
      val cur = lines(lineNum)
      if (linePos < cur.length) {
        val c = cur.charAt(linePos)
        linePos += 1
        return c
      }
      lineNum += 1
      linePos = 0
    }
    -1
  }

  override def close(): Unit = Unit
}
