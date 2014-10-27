package com.keepit.search.util

import scala.collection.mutable.ArrayBuffer

class IntArrayBuilder {
  private[this] var currentPage: Array[Int] = new Array[Int](8)
  private[this] var offset = 0
  private[this] var doneSize: Int = 0
  private[this] val donePages = new ArrayBuffer[Array[Int]]

  private def allocatePage(): Unit = {
    var pageSize = currentPage.length
    donePages += currentPage
    doneSize += pageSize

    pageSize = doneSize
    if (pageSize > 1024) pageSize = 1024
    currentPage = new Array[Int](pageSize)
    offset = 0
  }

  def +=(value: Int) = {
    if (offset >= currentPage.length) allocatePage()
    currentPage(offset) = value
    offset += 1
  }

  def toArray(): Array[Int] = {
    val array = new Array[Int](doneSize + offset)

    val ptr = donePages.foldLeft(0) { (ptr, page) =>
      val len = page.length
      System.arraycopy(page, 0, array, ptr, len)
      ptr + len
    }
    System.arraycopy(currentPage, 0, array, ptr, offset)

    array
  }
}
